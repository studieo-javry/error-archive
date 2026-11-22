package org.studieojavry.gateway.filter

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 레이트리밋 버킷 키가 실제 클라이언트 IP 인지 — 실제 Tomcat 으로 기동해 RemoteIpValve 동작까지 검증한다.
 *
 * 테스트 클라이언트는 127.0.0.1 에서 접속하므로, 127.0.0.1 을 신뢰 프록시(= 운영의 OCI LB 역할)로 둔다.
 * 대상은 export 라우트(버스트 5). 다운스트림이 없어도 레이트리밋은 프록시 전에 판정되므로 429 여부만 본다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["server.tomcat.remoteip.internal-proxies=127.0.0.1/32"],
)
@ActiveProfiles("test")
class ClientIpRateLimitIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun export(slug: String, forwardedFor: String?): Int {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port/p/$slug.pdf")).GET()
        forwardedFor?.let { builder.header("X-Forwarded-For", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `신뢰 프록시를 거친 사용자는 각자 버킷을 쓴다`() {
        repeat(5) { export("a", "203.0.113.1") }
        assertEquals(429, export("a", "203.0.113.1"), "같은 사용자는 6번째에 막힌다")
        assertNotEquals(429, export("a", "203.0.113.2"), "다른 사용자는 영향받지 않는다")
    }

    @Test
    fun `앞에 위조 값을 넣어도 프록시가 붙인 마지막 값으로 센다`() {
        repeat(5) { i -> export("b", "198.51.100.$i, 203.0.113.10") }
        assertEquals(429, export("b", "198.51.100.99, 203.0.113.10"))
    }

    companion object {
        /** 기동에 필요한 값만 채운다. 다운스트림은 호출되지 않아도 되므로 닫힌 포트를 준다. */
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("gateway.downstream.iam-api") { "http://localhost:59994" }
            registry.add("gateway.downstream.core-api") { "http://localhost:59995" }
            registry.add("internal-auth.issuer.private-key-pem") { privateKeyPem() }
        }

        private fun privateKeyPem(): String {
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
            return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
        }
    }
}
