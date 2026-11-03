package org.studieojavry.coreapi.errorcase.github

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder
import org.springframework.http.HttpHeaders
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import org.studieojavry.coreapi.errorcase.github.infrastructure.api.GithubAppApiAdapter
import org.studieojavry.coreapi.errorcase.github.infrastructure.api.GithubRateLimitTracker
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GitHub 호출 한도 추적 — 응답 헤더 기록, 백그라운드 예비분, 바닥나면 부르지 않고 한도 초과. 가짜 GitHub 는 JDK HttpServer. */
class GithubRateLimitTest {

    private val properties = GithubAppProperties(
        enabled = true, appId = "1", appSlug = "s", clientId = "c", clientSecret = "c",
        privateKeyPem = pkcs8Pem(), webhookSecret = "w", stateSecret = "s",
    )
    private val tracker = GithubRateLimitTracker(properties)
    private val now = Instant.parse("2026-11-03T05:00:00Z")

    private fun headers(remaining: Int, resetAt: Instant, resource: String = "core", limit: Int = 5000) = HttpHeaders().apply {
        add("x-ratelimit-limit", limit.toString())
        add("x-ratelimit-remaining", remaining.toString())
        add("x-ratelimit-reset", resetAt.epochSecond.toString())
        add("x-ratelimit-resource", resource)
    }

    // ── tracker ─────────────────────────────────────────────
    @Test
    fun `백그라운드는 잔량이 한도의 20퍼센트 이하면 리셋까지 기다림 - 모르는 설치·넉넉한 잔량·지난 리셋은 막지 않음`() {
        val reset = now.plusSeconds(600)
        assertNull(tracker.backgroundWait(1, now), "응답을 본 적 없음")

        tracker.record(1, headers(1001, reset))
        assertNull(tracker.backgroundWait(1, now))

        tracker.record(1, headers(1000, reset, resource = "graphql"))
        assertEquals(Duration.ofSeconds(600), tracker.backgroundWait(1, now), "graphql 이 예비분에 닿음")
        assertNull(tracker.backgroundWait(2, now), "다른 설치와는 무관")
        assertNull(tracker.backgroundWait(1, reset.plusSeconds(1)), "리셋이 지나면 새 창")
    }

    @Test
    fun `같은 창에서는 더 적은 잔량을 믿고, 늦게 온 지난 창 응답은 무시, 새 창이면 바꿈`() {
        val reset = now.plusSeconds(600)
        tracker.record(1, headers(0, reset))
        tracker.record(1, headers(40, reset))   // 순서 뒤바뀐 동시 응답
        assertEquals(Duration.ofSeconds(600), tracker.exhaustedWait(1, "core", now))
        tracker.record(1, headers(4999, now.minusSeconds(10)))   // 지난 창
        assertNotNull(tracker.exhaustedWait(1, "core", now))
        tracker.record(1, headers(4999, now.plusSeconds(4200)))   // 새 창
        assertNull(tracker.exhaustedWait(1, "core", now))
        assertNull(tracker.exhaustedWait(1, "graphql", now), "자원별로 따로")
        tracker.record(1, headers(0, reset, resource = "search"))
        assertNull(tracker.backgroundWait(1, now), "search 등 다른 자원은 보지 않음")
    }

    // ── adapter ─────────────────────────────────────────────
    private lateinit var server: HttpServer
    private val checkRunCalls = AtomicInteger()
    @Volatile private var remaining = 4000
    @Volatile private var resetAt = Instant.now().plusSeconds(900)

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/app/installations/7/access_tokens") { ex ->
                // App JWT 호출 — 한도가 따로라 기록되면 안 됨(잔량 0 으로 보내 확인)
                ex.responseHeaders.add("x-ratelimit-limit", "5000")
                ex.responseHeaders.add("x-ratelimit-remaining", "0")
                ex.responseHeaders.add("x-ratelimit-reset", Instant.now().plusSeconds(900).epochSecond.toString())
                ex.responseHeaders.add("Content-Type", "application/json")
                val body = """{"token":"ghs_test","expires_at":"${Instant.now().plusSeconds(3600)}"}""".toByteArray()
                ex.sendResponseHeaders(201, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            createContext("/repos/acme/api/commits/abc/check-runs") { ex ->
                checkRunCalls.incrementAndGet()
                ex.responseHeaders.add("x-ratelimit-limit", "5000")
                ex.responseHeaders.add("x-ratelimit-remaining", remaining.toString())
                ex.responseHeaders.add("x-ratelimit-reset", resetAt.epochSecond.toString())
                ex.responseHeaders.add("x-ratelimit-resource", "core")
                ex.responseHeaders.add("Content-Type", "application/json")
                val body = """{"total_count":0,"check_runs":[]}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private fun adapter() = GithubAppApiAdapter(
        properties.copy(apiBaseUrl = "http://127.0.0.1:${server.address.port}"), DirectCircuitBreakerFactory(), tracker,
    )

    @Test
    fun `어댑터 - installation 호출 응답 헤더를 기록, 바닥나면 GitHub 를 부르지 않고 retryAfter 로 실패`() {
        val api = adapter()
        api.fetchCommitCheckRuns(7, "acme", "api", "abc")
        assertNull(tracker.exhaustedWait(7, "core"), "토큰 발급(App JWT) 응답의 잔량 0 은 기록하지 않음")
        assertNull(tracker.backgroundWait(7))

        remaining = 900
        api.fetchCommitCheckRuns(7, "acme", "api", "abc")
        assertNotNull(tracker.backgroundWait(7), "예비분(1000) 이하 → 백그라운드 멈춤")
        assertNull(tracker.exhaustedWait(7, "core"), "사용자 요청은 계속")

        remaining = 0
        api.fetchCommitCheckRuns(7, "acme", "api", "abc")
        assertEquals(3, checkRunCalls.get())
        val ex = assertFailsWith<GithubApiException> { api.fetchCommitCheckRuns(7, "acme", "api", "abc") }
        assertEquals(3, checkRunCalls.get(), "바닥났으면 부르지 않음")
        val wait = assertNotNull(ex.retryAfter)
        assertTrue(wait > Duration.ofMinutes(14) && wait <= Duration.ofMinutes(15), "$wait")
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
