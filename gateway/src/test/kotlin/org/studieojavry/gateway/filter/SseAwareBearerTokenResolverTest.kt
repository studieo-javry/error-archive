package org.studieojavry.gateway.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * SseAwareBearerTokenResolver 단위 테스트.
 *
 * 운영 관점 핵심 계약: `?ticket=` 은 **SSE 경로에서만** 추출한다. 다른 경로에서 ticket 을 받으면
 * EventSource 우회 채널이 일반 API 로 새는 셈이라 격리가 깨진다. Authorization 헤더가 항상 우선.
 */
class SseAwareBearerTokenResolverTest {

    private val resolver = SseAwareBearerTokenResolver()
    private val ssePath = "/api/v1/users/me/notifications/stream"

    private fun request(uri: String, authHeader: String? = null, ticket: String? = null) =
        MockHttpServletRequest().apply {
            requestURI = uri
            authHeader?.let { addHeader("Authorization", it) }
            ticket?.let { setParameter("ticket", it) }
        }

    @Test
    fun `Authorization 헤더가 있으면 그 토큰을 우선 추출`() {
        assertEquals("abc.def.ghi", resolver.resolve(request("/api/v1/error-cases", authHeader = "Bearer abc.def.ghi")))
    }

    @Test
    fun `SSE 경로에서 헤더가 없으면 ticket 쿼리를 fallback 으로 추출`() {
        assertEquals("sse.ticket.jwt", resolver.resolve(request(ssePath, ticket = "sse.ticket.jwt")))
    }

    @Test
    fun `헤더가 있으면 SSE 경로여도 헤더가 우선 (ticket 무시)`() {
        assertEquals("hdr.token", resolver.resolve(request(ssePath, authHeader = "Bearer hdr.token", ticket = "tkt")))
    }

    @Test
    fun `비 SSE 경로에서는 ticket 쿼리를 무시한다`() {
        assertNull(resolver.resolve(request("/api/v1/error-cases", ticket = "sse.ticket.jwt")))
    }

    @Test
    fun `SSE 경로여도 ticket 이 없으면 null`() {
        assertNull(resolver.resolve(request(ssePath)))
    }

    @Test
    fun `SSE 경로 + 공백 ticket 은 null`() {
        assertNull(resolver.resolve(request(ssePath, ticket = "   ")))
    }
}
