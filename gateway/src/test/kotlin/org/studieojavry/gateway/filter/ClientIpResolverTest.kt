package org.studieojavry.gateway.filter

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpResolverTest {

    private val lb = "10.0.0.20"
    private val resolver = ClientIpResolver(ClientIpProperties(trustedProxies = listOf("10.0.0.0/24")))

    @Test
    fun `신뢰 대역 피어면 X-Forwarded-For 값을 쓴다`() {
        assertEquals("203.0.113.7", resolver.resolve(lb, "203.0.113.7"))
    }

    @Test
    fun `클라이언트가 앞에 넣은 위조 값은 무시하고 LB 가 붙인 마지막 값을 쓴다`() {
        assertEquals("203.0.113.7", resolver.resolve(lb, "1.2.3.4, 5.6.7.8, 203.0.113.7"))
    }

    @Test
    fun `직접 접속이 헤더를 위조해도 피어 주소를 쓴다`() {
        assertEquals("198.51.100.9", resolver.resolve("198.51.100.9", "1.2.3.4"))
    }

    @Test
    fun `헤더가 비면 신뢰 대역 피어 주소로 폴백`() {
        assertEquals(lb, resolver.resolve(lb, " "))
    }

    @Test
    fun `신뢰 대역이 비어 있으면 헤더를 믿지 않는다`() {
        val empty = ClientIpResolver(ClientIpProperties())
        assertEquals(lb, empty.resolve(lb, "1.2.3.4"))
    }

    @Test
    fun `피어를 알 수 없으면 헤더를 믿지 않는다`() {
        assertEquals("unknown", resolver.resolve(null, "1.2.3.4"))
    }

    @Test
    fun `ForwardedHeaderFilter 같은 래퍼가 remoteAddr 를 바꿔도 실제 소켓 주소를 읽는다`() {
        val socket = MockHttpServletRequest().apply { remoteAddr = "198.51.100.9" }
        val spoofed = object : HttpServletRequestWrapper(socket as HttpServletRequest) {
            override fun getRemoteAddr() = "1.2.3.4"
        }
        assertEquals("198.51.100.9", resolver.socketPeerAddress(spoofed))
    }
}
