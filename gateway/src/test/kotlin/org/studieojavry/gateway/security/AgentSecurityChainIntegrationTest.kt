package org.studieojavry.gateway.security

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #144 P0 — 게이트웨이 에이전트 보안 체인의 "통합 환경" 검증.
 *
 * 검증 대상(미체크였던 3건):
 *  1. 보안 체인 구성·실행 순서 — 에이전트 체인(@Order(1), 에이전트 경로 전용)이 기본 체인보다 먼저 매칭.
 *  2. 토큰 발급 → agent me 호출 → 폐기 → 재호출 차단 E2E.
 *  3. 토큰 만료 / IAM 장애 / 외부 내부인증 헤더 덮어쓰기.
 *
 * iam introspection 과 core-api 다운스트림은 의존성 없는 JDK HttpServer 스텁으로 대체한다.
 * 스텁은 introspection 응답(200/401/503)을 테스트마다 바꿔 발급·폐기·만료·장애를 재현하고,
 * 프록시된 에이전트 요청이 실어오는 X-Internal-Auth 를 포착해 위조 방지를 확인한다.
 * 게이트웨이가 실어 보내는 내부 JWT 는 런타임 생성한 RSA 키의 공개키로 서명 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgentSecurityChainIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var securityFilterChains: List<SecurityFilterChain>

    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun resetStub() {
        stub.reset()
    }

    // ---- 1. 체인 구성·실행 순서 -------------------------------------------------

    @Test
    fun `에이전트 체인이 기본 체인보다 먼저 매칭되고 에이전트 경로만 담당한다`() {
        assertTrue(securityFilterChains.size >= 2, "보안 체인이 2개 이상이어야 한다(에이전트 + 기본)")

        val agentReq = MockHttpServletRequest("GET", "/api/v1/agent/me")
        val webReq = MockHttpServletRequest("GET", "/api/v1/error-cases")

        // 최우선(@Order(1)) 체인이 에이전트 전용이고, 일반 웹 경로는 매칭하지 않아야 한다.
        // (기본 체인은 securityMatcher 가 없어 모든 경로를 매칭하므로 — 런타임은 first-match-wins —
        //  "에이전트 경로를 매칭하는 체인이 정확히 하나"가 아니라 "최우선 체인이 에이전트 전용"을 단언한다.)
        val first = securityFilterChains.first()
        assertTrue(first.matches(agentReq), "첫(최우선) 체인은 에이전트 경로를 매칭해야 한다")
        assertTrue(!first.matches(webReq), "에이전트(최우선) 체인은 일반 웹 경로를 매칭하면 안 된다(경로 격리)")
        assertTrue(securityFilterChains.any { it.matches(webReq) }, "일반 웹 경로는 기본 체인이 담당해야 한다")
    }

    @Test
    fun `자격증명 없는 에이전트 요청은 에이전트 필터가 401 로 차단한다`() {
        val res = get("/api/v1/agent/me", token = null)
        assertEquals(401, res.statusCode())
        assertEquals(0, stub.introspectCalls.get(), "자격증명이 없으면 introspection 도 호출되지 않는다")
        assertEquals(0, stub.downstreamCalls.get())
    }

    // ---- 2. 발급 → agent me → 폐기 → 재호출 차단 E2E ----------------------------

    @Test
    fun `발급된 토큰으로 agent me 가 통과하고 폐기 후 재호출은 차단된다`() {
        stub.introspectStatus.set(200) // (발급) iam 이 유효 연결로 해석

        val ok = get("/api/v1/agent/me", token = "eaa_live_token")
        assertEquals(200, ok.statusCode())
        assertEquals(1, stub.introspectCalls.get())
        assertEquals(1, stub.downstreamCalls.get(), "성공 시 core-api 로 프록시되어야 한다")

        val forwarded = stub.lastInternalAuth.get()
        assertThat(forwarded).isNotBlank()
        val claims = verifyGatewayToken(forwarded!!)
        assertEquals("gateway", claims.issuer)
        assertTrue(claims.audience.contains("core-api"), "aud 는 core-api 여야 한다")
        assertEquals("42", claims.subject, "sub = userId")
        assertEquals("conn-abc", claims.getStringClaim("cid"))
        assertEquals(listOf("capture:read", "capture:write"), claims.getStringListClaim("scp"))

        stub.introspectStatus.set(401) // (폐기) iam 이 이제 401
        val revoked = get("/api/v1/agent/me", token = "eaa_live_token")
        assertEquals(401, revoked.statusCode())
        assertEquals(1, stub.downstreamCalls.get(), "폐기 후에는 core-api 로 전달되면 안 된다")
    }

    // ---- 3. 만료 / IAM 장애 / 헤더 덮어쓰기 --------------------------------------

    @Test
    fun `만료되었거나 무효한 토큰은 401 로 거부된다`() {
        stub.introspectStatus.set(401) // iam 은 만료·무효·폐기를 모두 401 로 응답(상태코드가 계약)
        val res = get("/api/v1/agent/me", token = "eaa_expired_token")
        assertEquals(401, res.statusCode())
        assertEquals(0, stub.downstreamCalls.get())
    }

    @Test
    fun `IAM 장애(5xx)면 503 + Retry-After 로 매핑되고 다운스트림을 호출하지 않는다`() {
        stub.introspectStatus.set(503)
        val res = get("/api/v1/agent/me", token = "eaa_live_token")

        // 보안 핵심 불변식: 인증 불가 → core-api 로 절대 전달되지 않는다.
        assertEquals(0, stub.downstreamCalls.get(), "인증을 못 하면 core-api 로 전달되면 안 된다")

        // 토큰이 아니라 인증 서비스(iam)가 일시 장애 → 503(401 아님)으로 구분해, 커넥터가
        // 백오프·재시도하게 한다. sendError 대신 ProblemDetail 직접 기록이라 /error 재디스패치로
        // 상태가 뒤집히지 않는다(이전엔 503 의도가 401 로 귀결되던 FINDING 을 수정).
        assertEquals(503, res.statusCode())
        assertEquals("30", res.headers().firstValue("Retry-After").orElse(null), "재시도 시점 안내(Retry-After)")
    }

    @Test
    fun `외부가 보낸 내부인증 헤더는 게이트웨이가 덮어써 위조본이 전달되지 않는다`() {
        stub.introspectStatus.set(200)
        val forgedValue = "forged.attacker.token"

        val res = get("/api/v1/agent/me", token = "eaa_live_token", extraHeaders = listOf("X-Internal-Auth" to forgedValue))
        assertEquals(200, res.statusCode())

        val forwarded = stub.lastInternalAuth.get()
        assertThat(forwarded).isNotBlank()
        assertThat(forwarded).`as`("위조 헤더가 그대로 전달되면 안 된다").isNotEqualTo(forgedValue)
        val claims = verifyGatewayToken(forwarded!!)
        assertEquals("gateway", claims.issuer)
        assertTrue(claims.audience.contains("core-api"))
    }

    // ---- helpers ---------------------------------------------------------------

    private fun get(
        path: String,
        token: String?,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        if (token != null) builder.header("Authorization", "Bearer $token")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun verifyGatewayToken(token: String): JWTClaimsSet {
        val signed = SignedJWT.parse(token)
        assertTrue(signed.verify(RSASSAVerifier(publicKey)), "게이트웨이 서명 검증 실패")
        return signed.jwtClaimsSet
    }

    // ---- 스텁 백엔드 + 키 + 동적 프로퍼티 ---------------------------------------

    companion object {
        private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val publicKey = keyPair.public as RSAPublicKey
        private val stub = StubBackend().also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("gateway.downstream.iam-api") { stub.baseUrl() }
            registry.add("gateway.downstream.core-api") { stub.baseUrl() }
            registry.add("internal-auth.issuer.private-key-pem") { privateKeyPem() }
        }

        @JvmStatic
        @AfterAll
        fun stopStub() {
            stub.stop()
        }

        private fun privateKeyPem(): String {
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
            return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
        }
    }

    /**
     * iam introspection(POST /internal/agent-connections/introspect)과
     * 프록시된 core-api(에이전트 경로)를 한 포트에서 흉내 내는 최소 스텁.
     */
    class StubBackend {
        private lateinit var server: HttpServer
        val introspectStatus = AtomicInteger(200)
        val introspectCalls = AtomicInteger(0)
        val downstreamCalls = AtomicInteger(0)
        val lastInternalAuth = AtomicReference<String?>(null)

        fun start() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/internal/agent-connections/introspect") { ex ->
                introspectCalls.incrementAndGet()
                drain(ex.requestBody)
                when (val status = introspectStatus.get()) {
                    200 -> respond(ex, 200, """{"userId":42,"connectionId":"conn-abc","scopes":["capture:read","capture:write"]}""")
                    else -> respond(ex, status, "")
                }
            }
            server.createContext("/api/v1/agent") { ex ->
                downstreamCalls.incrementAndGet()
                lastInternalAuth.set(ex.requestHeaders.getFirst("X-Internal-Auth"))
                drain(ex.requestBody)
                respond(ex, 200, """{"ok":true}""")
            }
            server.executor = null
            server.start()
        }

        fun reset() {
            introspectStatus.set(200)
            introspectCalls.set(0)
            downstreamCalls.set(0)
            lastInternalAuth.set(null)
        }

        fun baseUrl() = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)

        private fun drain(body: InputStream) = body.use { it.readBytes() }

        private fun respond(ex: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            if (bytes.isEmpty()) {
                ex.sendResponseHeaders(status, -1)
            } else {
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }
    }
}
