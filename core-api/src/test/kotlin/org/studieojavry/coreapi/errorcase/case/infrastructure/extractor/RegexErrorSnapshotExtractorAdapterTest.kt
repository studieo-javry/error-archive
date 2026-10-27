package org.studieojavry.coreapi.errorcase.case.infrastructure.extractor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 정규식 추출기 — 정상 예외 추출 보존 + **과대추출(false positive) 방지** 회귀.
 * 부팅 배너의 일반 타입명(java.lang.String)·Go 함수명(debug.Stack)·로거명을 예외로 잡지 않아야 한다.
 * (잡으면 UseCase 의 '정규식 우선' 병합이 LLM 의 올바른 null 을 덮어 오답을 사실로 확정함.)
 */
class RegexErrorSnapshotExtractorAdapterTest {

    private val sut = RegexErrorSnapshotExtractorAdapter()

    @Test
    fun `표준 예외 헤더에서 클래스와 메시지를 추출한다`() {
        val r = sut.extract(
            """
            java.lang.NullPointerException: coupon is null
            ${"\t"}at com.shop.OrderService.calculateTotal(OrderService.java:42)
            """.trimIndent(),
        )
        assertEquals("java.lang.NullPointerException", r.exceptionClass)
        assertEquals("coupon is null", r.exceptionMessage)
    }

    @Test
    fun `Exception in thread 접두를 떼고 추출한다`() {
        val r = sut.extract(
            """
            Exception in thread "main" java.lang.IllegalStateException: boom
            ${"\t"}at com.ea.Foo.bar(Foo.kt:10)
            """.trimIndent(),
        )
        assertEquals("java.lang.IllegalStateException", r.exceptionClass)
    }

    @Test
    fun `Caused by 루트원인을 우선 추출한다`() {
        val r = sut.extract(
            """
            com.ea.OuterException: wrap
            Caused by: java.net.SocketTimeoutException: Read timed out
            ${"\t"}at java.base/java.net.SocketInputStream.read(SocketInputStream.java:183)
            """.trimIndent(),
        )
        assertEquals("java.net.SocketTimeoutException", r.exceptionClass)
    }

    @Test
    fun `부팅 배너의 일반 타입명(java_lang_String)을 예외로 잡지 않는다`() {
        val r = sut.extract(
            """
            APPLICATION FAILED TO START
            Description:
            Failed to bind properties under 'spring.datasource.url' to java.lang.String:
                Reason: Could not resolve placeholder 'DB_URL' in value "${'$'}{DB_URL}"
            """.trimIndent(),
        )
        assertNull(r.exceptionClass)
    }

    @Test
    fun `Go recover 스택의 함수명(debug_Stack)을 예외로 잡지 않는다`() {
        val r = sut.extract(
            """
            goroutine 17 [running]:
            runtime/debug.Stack()
                /usr/local/go/src/runtime/debug/stack.go:26 +0x5e
            main.parseRow(...)
                /app/csv/parse.go:44 +0x11c
            """.trimIndent(),
        )
        assertNull(r.exceptionClass)
    }

    @Test
    fun `로거명이 섞인 로그 줄을 예외로 잡지 않는다`() {
        val r = sut.extract("2026-10-26 11:20:01 INFO  c.e.OrderService - processing order 5512")
        assertNull(r.exceptionClass)
    }
}
