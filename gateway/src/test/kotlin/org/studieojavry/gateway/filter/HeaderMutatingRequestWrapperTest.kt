package org.studieojavry.gateway.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.util.Collections

/**
 * HeaderMutatingRequestWrapper 단위 테스트.
 *
 * HeaderInjectionFilter 가 이 래퍼로 X-Internal-Auth 를 끼워 넣으므로, 대소문자 무관 조회와
 * 원본 헤더 덮어쓰기가 정확해야 한다 (틀리면 신원 헤더가 downstream 에 이중/누락 전달될 수 있음).
 */
class HeaderMutatingRequestWrapperTest {

    private fun wrap(vararg original: Pair<String, String>): HeaderMutatingRequestWrapper {
        val req = MockHttpServletRequest().apply { original.forEach { (k, v) -> addHeader(k, v) } }
        return HeaderMutatingRequestWrapper(req)
    }

    @Test
    fun `putHeader 는 대소문자 무관하게 조회된다`() {
        val w = wrap().apply { putHeader("X-Internal-Auth", "tok") }
        assertEquals("tok", w.getHeader("x-internal-auth"))
        assertEquals("tok", w.getHeader("X-INTERNAL-AUTH"))
    }

    @Test
    fun `커스텀 헤더가 원본 헤더를 덮어쓴다`() {
        val w = wrap("X-Foo" to "orig").apply { putHeader("X-Foo", "new") }
        assertEquals("new", w.getHeader("X-Foo"))
        assertEquals(listOf("new"), Collections.list(w.getHeaders("X-Foo")))
    }

    @Test
    fun `설정하지 않은 헤더는 원본을 그대로 반환`() {
        val w = wrap("X-Bar" to "keep")
        assertEquals("keep", w.getHeader("X-Bar"))
        assertNull(w.getHeader("X-Nonexistent"))
    }

    @Test
    fun `getHeaderNames 는 원본과 커스텀 헤더를 모두 포함`() {
        val w = wrap("X-Orig" to "1").apply { putHeader("X-Custom", "2") }
        val names = Collections.list(w.headerNames).map { it.lowercase() }
        assertTrue(names.contains("x-orig"), "원본 헤더 포함")
        assertTrue(names.contains("x-custom"), "커스텀 헤더 포함")
    }
}
