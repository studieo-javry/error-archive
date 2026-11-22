package org.studieojavry.gateway.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class FrameOptionsHeaderWriterTest {

    private fun frameOptionsFor(path: String, upstream: String? = null): String? {
        val request = MockHttpServletRequest("GET", path)
        val response = MockHttpServletResponse()
        upstream?.let { response.setHeader("X-Frame-Options", it) }
        FrameOptionsHeaderWriter.writeHeaders(request, response)
        return response.getHeader("X-Frame-Options")
    }

    @Test
    fun `공개 페이지는 같은 출처 iframe 을 허용한다`() {
        assertEquals("SAMEORIGIN", frameOptionsFor("/p/some-slug"))
    }

    @Test
    fun `공개 페이지 PDF 도 SAMEORIGIN`() {
        assertEquals("SAMEORIGIN", frameOptionsFor("/p/some-slug.pdf"))
    }

    @Test
    fun `API 는 DENY`() {
        assertEquals("DENY", frameOptionsFor("/api/v1/error-cases"))
    }

    @Test
    fun `p 로 시작하지만 다른 경로는 DENY`() {
        assertEquals("DENY", frameOptionsFor("/publishments"))
    }

    @Test
    fun `다운스트림 값이 있어도 경로 정책으로 덮어쓴다`() {
        assertEquals("DENY", frameOptionsFor("/api/v1/x", upstream = "SAMEORIGIN"))
        assertEquals("SAMEORIGIN", frameOptionsFor("/p/x", upstream = "DENY"))
    }
}
