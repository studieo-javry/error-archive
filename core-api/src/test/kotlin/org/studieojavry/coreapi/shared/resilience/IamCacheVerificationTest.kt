package org.studieojavry.coreapi.shared.resilience

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.CacheManager
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.studieojavry.coreapi.errorcase.shared.application.port.IamUserQueryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.shared.config.CacheConfig
import org.studieojavry.coreapi.shared.config.DashboardCacheInvalidator
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Redis 분산 캐시 도입 검증 (1·2순위 중 **2순위: iam 동기호출 캐시** + 1순위 evict 정확성).
 *
 * **실제** Redis(로컬 redis-server 를 ephemeral 포트에 기동) + 실제 CacheManager(RedisCacheManager) +
 * 실제 어댑터/CB 를, 요청 수를 세는 **mock iam-api** 를 향해 호출해 다음을 확인한다:
 *
 *  1. backend 전환: `core.cache.backend=redis` 일 때 CacheManager 가 RedisCacheManager 로 바뀐다.
 *  2. role 캐시: `getViewerRole` 2회 → iam 워크스페이스 호출은 **1회**(두 번째는 캐시 히트, 호출 없음).
 *  3. profile 캐시: `findByIds` 2회 → iam 유저 호출은 **1회**.
 *  4. null 은 캐시하지 않음(안전): 비멤버(403→null) 2회 조회 → iam 호출 **2회**(거부가 고착되지 않음).
 *  5. evict 정확성: `evictRecentActive(4)` 는 Redis 에서 `4` / `4:*` 만 지우고 `42:*` 는 보존.
 *
 * (단일 JVM·H2·로컬 redis — production 멀티 인스턴스로 확대 해석하지 않음. 캐시 '공유'의 이점은
 *  구조적 근거이며 이 테스트는 캐시 동작·정확성만 검증한다.)
 */
@SpringBootTest
@ActiveProfiles("test")
class IamCacheVerificationTest {

    @Autowired private lateinit var workspaceQuery: WorkspaceQueryPort
    @Autowired private lateinit var userQuery: IamUserQueryPort
    @Autowired private lateinit var cacheManager: CacheManager
    @Autowired private lateinit var invalidator: DashboardCacheInvalidator

    @BeforeEach
    fun setUp() {
        workspaceHits.set(0)
        userHits.set(0)
        // 캐시 잔여 제거 — 테스트 간 격리(같은 redis 인스턴스 공유).
        listOf(
            CacheConfig.CACHE_WORKSPACE_ROLE,
            CacheConfig.CACHE_IAM_USER_PROFILE,
            CacheConfig.CACHE_RECENT_ACTIVE_CASES,
        ).forEach { cacheManager.getCache(it)?.clear() }
        // 인터셉터가 internal JWT 를 붙이도록 인증 컨텍스트 세팅.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("100", "n/a", listOf(SimpleGrantedAuthority("ROLE_USER")))
    }

    @Test
    @DisplayName("1 · core.cache.backend=redis → CacheManager 가 RedisCacheManager")
    fun backend_is_redis() {
        assertInstanceOf(RedisCacheManager::class.java, cacheManager, "Redis 백엔드로 전환되어야 함")
    }

    @Test
    @DisplayName("2 · getViewerRole 2회 → iam 워크스페이스 호출 1회(두 번째는 캐시 히트)")
    fun role_lookup_is_cached() {
        val first = workspaceQuery.getViewerRole(100L, 7L)
        val second = workspaceQuery.getViewerRole(100L, 7L)

        assertEquals(WorkspaceQueryPort.WorkspaceRole.ADMIN, first)
        assertEquals(WorkspaceQueryPort.WorkspaceRole.ADMIN, second)
        assertEquals(1, workspaceHits.get(), "두 번째 호출은 캐시 히트 → iam 왕복 없음")
    }

    @Test
    @DisplayName("3 · findByIds 2회 → iam 유저 호출 1회(프로필 캐시)")
    fun profile_lookup_is_cached() {
        val first = userQuery.findByIds(listOf(7L))
        val second = userQuery.findByIds(listOf(7L))

        assertEquals(1, first.size)
        assertEquals("홍길동", first.first().displayName)
        assertEquals(first, second)
        assertEquals(1, userHits.get(), "같은 userId 는 캐시 → iam 왕복 1회")
    }

    @Test
    @DisplayName("4 · 비멤버(403→null)는 캐시하지 않음 → 2회 조회 모두 iam 호출(거부 고착 방지)")
    fun null_role_is_not_cached() {
        val first = workspaceQuery.getViewerRole(100L, 999L) // 999 = mock 이 403 반환
        val second = workspaceQuery.getViewerRole(100L, 999L)

        assertNull(first)
        assertNull(second)
        assertEquals(2, workspaceHits.get(), "null 결과는 캐시 안 됨 → 매번 재조회(안전 방향)")
    }

    @Test
    @DisplayName("5 · evictRecentActive(4) 는 Redis 에서 4/4:* 만 지우고 42:* 는 보존(':' 경계)")
    fun evict_respects_user_boundary() {
        val cache = cacheManager.getCache(CacheConfig.CACHE_RECENT_ACTIVE_CASES)!!
        cache.put("4:7:20", "payload-user-4")
        cache.put("4", "payload-user-4-bare")
        cache.put("42:7:20", "payload-user-42")

        invalidator.evictRecentActive(4L)

        assertNull(cache.get("4:7:20"), "userId=4 의 variant 제거")
        assertNull(cache.get("4"), "userId=4 의 bare 키 제거")
        assertNotNull(cache.get("42:7:20"), "userId=42 는 영향 없음(경계 보존)")
    }

    companion object {
        private lateinit var mockIam: HttpServer
        private lateinit var redisProcess: Process
        private var redisPort: Int = 0
        private val workspaceHits = AtomicInteger(0)
        private val userHits = AtomicInteger(0)

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            startRedis()
            startMockIam()
            registry.add("core.cache.backend") { "redis" }
            registry.add("spring.data.redis.host") { "127.0.0.1" }
            registry.add("spring.data.redis.port") { redisPort }
            registry.add("core.iam-api.base-url") { "http://127.0.0.1:${mockIam.address.port}" }
        }

        private fun startRedis() {
            redisPort = java.net.ServerSocket(0).use { it.localPort }
            redisProcess = ProcessBuilder(
                "redis-server", "--port", redisPort.toString(), "--save", "", "--appendonly", "no",
            ).redirectErrorStream(true).start()
            // 준비될 때까지 PING(최대 ~5s).
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                runCatching {
                    Socket("127.0.0.1", redisPort).use { s ->
                        s.getOutputStream().write("PING\r\n".toByteArray())
                        s.getOutputStream().flush()
                        val resp = s.getInputStream().read()
                        if (resp == '+'.code) return // +PONG
                    }
                }
                Thread.sleep(50)
            }
            error("redis-server not ready on port $redisPort")
        }

        private fun startMockIam() {
            mockIam = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/api/v1/workspaces/") { ex ->
                    workspaceHits.incrementAndGet()
                    val id = ex.requestURI.path.substringAfterLast('/')
                    val (status, body) = if (id == "999") 403 to """{"message":"not a member"}"""
                        else 200 to """{"viewerRole":"ADMIN"}"""
                    respond(ex, status, body)
                }
                createContext("/api/v1/users/") { ex ->
                    userHits.incrementAndGet()
                    val id = ex.requestURI.path.substringAfterLast('/')
                    respond(ex, 200, """{"userId":$id,"displayName":"홍길동","avatarUrl":null}""")
                }
                executor = java.util.concurrent.Executors.newFixedThreadPool(4)
                start()
            }
        }

        private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            runCatching {
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            if (::mockIam.isInitialized) mockIam.stop(0)
            if (::redisProcess.isInitialized) redisProcess.destroyForcibly()
        }
    }
}
