package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #144 P0 — Agent Capture 동시성 검증. 실제 스레드로 레이스를 일으켜 Testcontainers Postgres
 * 에서 서버의 동시성 보장을 확인한다. 비결정적 결과는 "항상 성립하는 불변식"으로 단언하고,
 * 관찰된 상태코드 분포는 로그로 남겨 설계 특성/FINDING 을 드러낸다.
 *
 * 검증 3건:
 *  1. 동시 중복 요청(같은 Idempotency-Key·captureUuid·payload 동시 생성) → 중복 생성 0.
 *  2. 동시 수정(같은 expectedRevision 로 동시 PATCH) → 정확히 1건만 적용(revision +1).
 *  3. complete 와 Attempt 쓰기 경합 → 최종 봉인, 손상 없음.
 *
 * 아키텍처 사실: agent-capture 에는 비관적 락(SELECT FOR UPDATE)이 없고, capture 행의 JPA
 * @Version 낙관락 + UNIQUE 제약만이 동시성 제어다. 경합의 패자(낙관락 실패·유니크 위반)는
 * AgentCaptureExceptionHandler 가 각각 409 REVISION_CONFLICT / 409 CONCURRENT_REQUEST 로 매핑한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
])
class AgentCaptureConcurrencyIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var om: ObjectMapper
    private lateinit var mvc: MockMvc

    private val userId = 7001L
    private val connectionId = UUID.randomUUID().toString()
    private val writeScopes = listOf("capture:read", "capture:write")

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun agent() = authentication(
        InternalAuthentication(
            userId,
            writeScopes.map { SimpleGrantedAuthority("SCOPE_$it") },
            "gateway",
            connectionId,
            writeScopes,
        )
    )

    private data class Res(val status: Int, val code: String?, val body: String)

    /** 모든 작업을 CountDownLatch 로 동시에 풀어 진짜 레이스를 만든다. 결과는 작업 순서대로 반환. */
    private fun race(tasks: List<() -> Res>): List<Res> {
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            val gate = CountDownLatch(1)
            val futures = tasks.map { t -> pool.submit(Callable { gate.await(); t() }) }
            gate.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun createReq(uuid: UUID, key: String, title: String): Res {
        val res = mvc.perform(
            post("/api/v1/agent/captures").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "captureUuid" to uuid.toString(),
                    "title" to title,
                    "content" to mapOf("symptom" to "boom", "observedFacts" to listOf("x")),
                ))),
        ).andReturn().response
        return Res(res.status, codeOf(res.contentAsString), res.contentAsString)
    }

    private fun patchReq(uuid: UUID, expectedRevision: Int, title: String): Res {
        val res = mvc.perform(
            patch("/api/v1/agent/captures/$uuid").with(agent())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("expectedRevision" to expectedRevision, "title" to title))),
        ).andReturn().response
        return Res(res.status, codeOf(res.contentAsString), res.contentAsString)
    }

    private fun attemptReq(uuid: UUID, clientId: String, key: String): Res {
        val res = mvc.perform(
            post("/api/v1/agent/captures/$uuid/attempts").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "clientAttemptId" to clientId,
                    "result" to "FAILED",
                    "detail" to mapOf("title" to "try $clientId", "action" to "ran"),
                ))),
        ).andReturn().response
        return Res(res.status, codeOf(res.contentAsString), res.contentAsString)
    }

    private fun completeReq(uuid: UUID, key: String): Res {
        val res = mvc.perform(
            post("/api/v1/agent/captures/$uuid/complete").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andReturn().response
        return Res(res.status, codeOf(res.contentAsString), res.contentAsString)
    }

    private fun getCapture(uuid: UUID): Res {
        val res = mvc.perform(get("/api/v1/agent/captures/$uuid").with(agent())).andReturn().response
        return Res(res.status, codeOf(res.contentAsString), res.contentAsString)
    }

    private fun codeOf(body: String): String? =
        try { om.readTree(body).get("code")?.asText() } catch (_: Exception) { null }

    private fun dist(results: List<Res>): Map<String, Int> =
        results.groupingBy { "${it.status}${it.code?.let { c -> "/$c" } ?: ""}" }.eachCount()

    // ---- 1. 동시 중복 요청 → 중복 생성 없음 -------------------------------------

    @Test
    fun `같은 키-uuid-payload 로 동시 생성해도 중복 capture 가 생기지 않는다`() {
        val uuid = UUID.randomUUID()
        val n = 8
        val results = race((1..n).map { { createReq(uuid, "dup-key", "same") } })
        println("[CREATE race] " + dist(results))

        val successes = results.filter { it.status in 200..299 }
        assertTrue(successes.isNotEmpty(), "적어도 하나는 성공해야 한다")
        // 멱등 보장: 모든 성공 응답은 동일 captureUuid(= 중복 생성 없음). 뒤늦게 온 요청은 영수증 재생.
        val uuids = successes.map { om.readTree(it.body)["captureUuid"].asText() }.toSet()
        assertEquals(setOf(uuid.toString()), uuids, "모든 성공은 같은 captureUuid 여야 한다(중복 생성 금지)")
        // 실패가 있다면 전부 409 CONCURRENT_REQUEST 여야 한다(capture_uuid 유니크 위반 → 멱등 경합으로 매핑).
        // 멱등 키로 재전송하면 최초 결과가 재생된다. (수정 전엔 raw DataIntegrityViolation → 500 으로 샜다.)
        val failures = results.filter { it.status !in 200..299 }
        assertTrue(failures.all { it.status == 409 && it.code == "CONCURRENT_REQUEST" },
            "동시 중복 패자는 409 CONCURRENT_REQUEST — 관찰: ${dist(failures)}")

        // 최종적으로 단 하나의 OPEN capture(rev 0)만 존재.
        val got = getCapture(uuid)
        assertEquals(200, got.status)
        assertEquals("OPEN", om.readTree(got.body)["capture"]["status"].asText())
        assertEquals(0, om.readTree(got.body)["capture"]["revision"].asInt())
    }

    // ---- 2. 동시 수정 → 정확히 1건만 적용 --------------------------------------

    @Test
    fun `같은 revision 으로 동시 PATCH 하면 정확히 하나만 적용된다`() {
        val uuid = UUID.randomUUID()
        createReq(uuid, "k-c2", "orig")

        val results = race(listOf(
            { patchReq(uuid, 0, "edit-A") },
            { patchReq(uuid, 0, "edit-B") },
        ))
        println("[PATCH race] " + dist(results))

        val successes = results.filter { it.status in 200..299 }
        // 손실 수정 방지: 두 요청이 같은 revision 을 보면 하나만 적용돼야 한다.
        assertEquals(1, successes.size, "동시 수정 중 정확히 1건만 성공해야 한다")

        // 최종 revision 은 정확히 0→1 (단 한 번만 증가).
        val got = getCapture(uuid)
        assertEquals(1, om.readTree(got.body)["capture"]["revision"].asInt(), "revision 은 1 이어야 한다(한 번만 적용)")

        // 패자는 409 REVISION_CONFLICT 여야 한다 — 직렬화됐으면 stale expectedRevision 검사로,
        // 진짜 동시면 JPA 낙관락(ObjectOptimisticLockingFailureException)을 어드바이스가 매핑해서.
        // (수정 전엔 낙관락 미매핑으로 500 or github 의 CONNECTION_VERSION_CONFLICT 로 샜다.)
        val loser = results.single { it.status !in 200..299 }
        assertEquals(409, loser.status, "동시 수정 패자는 409 — 관찰 status=${loser.status} code=${loser.code}")
        assertEquals("REVISION_CONFLICT", loser.code, "동시 수정 패자 코드는 REVISION_CONFLICT")
    }

    // ---- 3. complete 와 Attempt 쓰기 경합 --------------------------------------

    @Test
    fun `complete 와 attempt 쓰기를 동시에 보내도 손상되지 않고 최종 봉인된다`() {
        val uuid = UUID.randomUUID()
        createReq(uuid, "k-c3", "orig")

        val results = race(listOf(
            { completeReq(uuid, "k-complete") },
            { attemptReq(uuid, "a1", "k-a1") },
        ))
        val complete = results[0]
        val attempt = results[1]
        println("[COMPLETE vs ATTEMPT] complete=${complete.status} attempt=${attempt.status}/${attempt.code}")

        // complete 는 capture 행만 봉인(attempt 는 자식만 저장 → @Version 경합 없음)하므로 항상 성공.
        assertEquals(200, complete.status, "complete 는 성공해야 한다")
        assertEquals("READY_FOR_REVIEW", om.readTree(getCapture(uuid).body)["capture"]["status"].asText())

        // attempt 는 봉인 커밋 전에 OPEN 을 읽으면 201, 후면 409 CAPTURE_SEALED. 500(손상) 은 안 된다.
        // [FINDING] 비관적 락이 없어 complete 와 add-attempt 가 직렬화되지 않는다. 따라서 201 이
        // 관찰될 수 있다 = "봉인되는 capture 에 Attempt 가 붙음"(봉인의 원자성이 자식 쓰기까지는 보장 안 됨).
        // 보안/정합 위협은 아니나(검토 단계에서 사용자가 확인), 원한다면 비관락/상태 재확인으로 강화 가능.
        assertTrue(attempt.status == 201 || attempt.status == 409,
            "attempt 는 201(생성) 또는 409(봉인거부) — 관찰: ${attempt.status}/${attempt.code}")
        if (attempt.status == 409) assertEquals("CAPTURE_SEALED", attempt.code)
    }
}
