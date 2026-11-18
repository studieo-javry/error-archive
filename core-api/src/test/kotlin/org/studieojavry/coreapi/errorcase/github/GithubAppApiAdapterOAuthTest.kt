package org.studieojavry.coreapi.errorcase.github

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import org.studieojavry.coreapi.errorcase.github.infrastructure.api.GithubAppApiAdapter
import org.studieojavry.coreapi.errorcase.github.infrastructure.api.GithubRateLimitTracker
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.function.Function
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GithubAppApiAdapter] 의 OAuth 토큰 교환/갱신 **응답 파싱**을 실제 HTTP 로 검증한다(가짜 GitHub = JDK HttpServer).
 * 포트 목으로는 못 잡는 것: `expires_in`/`refresh_token` 파싱, `expires_in → expiresAt` 산술, 갱신 폼의
 * `grant_type=refresh_token`, GitHub 특유의 "200 + {error}" 를 clientError 로 올리는지, 만료 설정이 꺼진
 * 응답(만료/refresh 필드 없음)을 null 로 두는지(= 무기한 토큰, 하위호환).
 */
class GithubAppApiAdapterOAuthTest {

    private val properties = GithubAppProperties(
        enabled = true, appId = "1", appSlug = "s", clientId = "cid", clientSecret = "secret",
        privateKeyPem = pkcs8Pem(), webhookSecret = "w", stateSecret = "s",
    )
    private val tracker = GithubRateLimitTracker(properties)

    private lateinit var server: HttpServer
    @Volatile private var oauthResponse: String = "{}"
    @Volatile private var lastForm: String? = null

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            // 교환·갱신 모두 같은 경로. 요청 폼을 기록하고, 테스트가 지정한 JSON 을 그대로(200) 돌려준다(GitHub 는 에러도 200).
            createContext("/login/oauth/access_token") { ex ->
                lastForm = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val body = oauthResponse.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    // OAuth 는 webBaseUrl 로 호출된다.
    private fun adapter() = GithubAppApiAdapter(
        properties.copy(webBaseUrl = "http://127.0.0.1:${server.address.port}"),
        DirectCircuitBreakerFactory(),
        tracker,
    )

    // ── exchangeUserCode ────────────────────────────────────────
    @Test
    fun `exchangeUserCode - 만료 설정 켜진 응답의 refresh·만료 파싱 + 폼에 code`() {
        oauthResponse = """
            {"access_token":"utok","token_type":"bearer","scope":"",
             "expires_in":28800,"refresh_token":"rtok","refresh_token_expires_in":15897600}
        """.trimIndent()

        val before = Instant.now()
        val token = adapter().exchangeUserCode("the-code")
        val after = Instant.now()

        assertEquals("utok", token.token)
        assertEquals("rtok", token.refreshToken)
        // expiresAt = 호출 시점 + 28800s 이므로 [before+28800, after+28800] 창 안.
        assertInWindow(token.expiresAt, before, after, 28800)
        assertInWindow(token.refreshTokenExpiresAt, before, after, 15897600)
        // 교환 폼엔 code 가 있고 grant_type 은 없다.
        assertTrue(lastForm!!.contains("code=the-code"), lastForm!!)
        assertTrue(!lastForm!!.contains("grant_type"), lastForm!!)
    }

    @Test
    fun `exchangeUserCode - 만료 설정 꺼진 응답(무기한)은 refresh·만료 null`() {
        // App 이 토큰 만료를 끄면 expires_in·refresh_token 자체가 안 온다 → null(= 무기한, 선제 갱신 안 함).
        oauthResponse = """{"access_token":"utok","token_type":"bearer","scope":"repo"}"""

        val token = adapter().exchangeUserCode("the-code")

        assertEquals("utok", token.token)
        assertNull(token.refreshToken)
        assertNull(token.expiresAt)
        assertNull(token.refreshTokenExpiresAt)
    }

    @Test
    fun `exchangeUserCode - 200 + error(잘못된 code)는 clientError`() {
        oauthResponse = """{"error":"bad_verification_code","error_description":"..."}"""

        val ex = assertFailsWith<GithubApiException> { adapter().exchangeUserCode("stale") }
        assertTrue(ex.clientError, "잘못된 code 는 재인가 신호 → clientError")
    }

    // ── refreshUserToken ────────────────────────────────────────
    @Test
    fun `refreshUserToken - 회전된 토큰 파싱 + 폼에 grant_type=refresh_token·refresh_token`() {
        oauthResponse = """
            {"access_token":"utok2","token_type":"bearer",
             "expires_in":28800,"refresh_token":"rtok2","refresh_token_expires_in":15897600}
        """.trimIndent()

        val before = Instant.now()
        val token = adapter().refreshUserToken("rtok-in")
        val after = Instant.now()

        assertEquals("utok2", token.token)
        assertEquals("rtok2", token.refreshToken) // GitHub 가 회전 → 새 값
        assertInWindow(token.expiresAt, before, after, 28800)
        assertInWindow(token.refreshTokenExpiresAt, before, after, 15897600)
        assertTrue(lastForm!!.contains("grant_type=refresh_token"), lastForm!!)
        assertTrue(lastForm!!.contains("refresh_token=rtok-in"), lastForm!!)
    }

    @Test
    fun `refreshUserToken - 200 + error(만료·취소된 refresh)는 clientError`() {
        oauthResponse = """{"error":"bad_refresh_token","error_description":"..."}"""

        val ex = assertFailsWith<GithubApiException> { adapter().refreshUserToken("dead") }
        assertTrue(ex.clientError, "죽은 refresh 토큰 → 폐기·재인가 신호 → clientError")
    }

    private fun assertInWindow(actual: Instant?, before: Instant, after: Instant, plusSeconds: Long) {
        val got = requireNotNull(actual) { "expected non-null instant" }
        assertTrue(
            got >= before.plusSeconds(plusSeconds) && got <= after.plusSeconds(plusSeconds),
            "expiresAt $got not in [${before.plusSeconds(plusSeconds)}, ${after.plusSeconds(plusSeconds)}]",
        )
    }

    /** 감싸기만 하는 circuit breaker — 실패는 fallback 으로 그대로 넘긴다. */
    private class DirectCircuitBreakerFactory : CircuitBreakerFactory<Unit, ConfigBuilder<Unit>>() {
        override fun create(id: String): CircuitBreaker = object : CircuitBreaker {
            override fun <T : Any> run(toRun: Supplier<T>, fallback: Function<Throwable?, T>): T =
                try { toRun.get() } catch (e: Throwable) { fallback.apply(e) }
        }

        override fun configBuilder(id: String): ConfigBuilder<Unit> = ConfigBuilder { }
        override fun configureDefault(defaultConfiguration: Function<String, Unit>) = Unit
    }

    companion object {
        private fun pkcs8Pem(): String {
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
            return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
        }
    }
}
