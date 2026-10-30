package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.case.application.port.DraftExtractionPort
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Severity
import org.studieojavry.coreapi.errorcase.case.infrastructure.extractor.RegexErrorSnapshotExtractorAdapter

/**
 * SuggestErrorDraftUseCase 단위 테스트 (LLM 없이도 도는 결정적 토대 + 병합/폴백/마스킹 검증).
 */
class SuggestErrorDraftUseCaseTest {

    private val regex = RegexErrorSnapshotExtractorAdapter()
    private fun useCase(llm: DraftExtractionPort) = SuggestErrorDraftUseCase(regex, llm)

    /** 입력을 캡처해 마스킹 검증에 쓰는 가짜 LLM 포트. */
    private class FixedLlm(private val out: DraftExtractionPort.Output?) : DraftExtractionPort {
        var lastInput: DraftExtractionPort.Input? = null
        override fun extract(input: DraftExtractionPort.Input): DraftExtractionPort.Output? {
            lastInput = input
            return out
        }
    }

    private class FailingLlm : DraftExtractionPort {
        override fun extract(input: DraftExtractionPort.Input): DraftExtractionPort.Output =
            throw RuntimeException("LLM down")
    }

    private val npePaste = """
        java.lang.NullPointerException: coupon is null
        ${"\t"}at com.shop.OrderService.calculateTotal(OrderService.kt:42)
        ${"\t"}at com.shop.OrderController.create(OrderController.kt:17)
    """.trimIndent()

    @Test
    fun `LLM 없으면 정규식 폴백으로 exceptionClass·제목 생성 degraded=true`() {
        val r = useCase(FixedLlm(null)).suggest(SuggestErrorDraftUseCase.Command(npePaste))

        assertEquals("java.lang.NullPointerException", r.exceptionClass)
        assertTrue(r.title.contains("NullPointerException"), "제목에 예외명이 들어가야 함: ${r.title}")
        assertTrue(r.degraded)
        assertTrue(r.tags.isEmpty())
        assertNull(r.severity)
    }

    @Test
    fun `LLM 호출이 예외를 던져도 폴백으로 흡수된다 degraded=true`() {
        val r = useCase(FailingLlm()).suggest(SuggestErrorDraftUseCase.Command(npePaste))

        assertEquals("java.lang.NullPointerException", r.exceptionClass)
        assertTrue(r.degraded)
    }

    @Test
    fun `LLM 결과 병합 - 사실은 정규식 우선, severity enum 검증, tags 소문자·중복제거·5개 상한`() {
        val llm = FixedLlm(
            DraftExtractionPort.Output(
                title = "주문 금액 합산 중 NPE",
                exceptionClass = "wrong.FakeException", // 정규식이 우선 → 무시돼야
                summary = "쿠폰 null 로 합산 시 NPE",
                tags = listOf("NPE", "npe", "Kotlin", "order", "payment", "extra6"), // 대문자·중복·6개
                severity = "s2",
                confidence = 0.82,
            ),
        )
        val r = useCase(llm).suggest(SuggestErrorDraftUseCase.Command(npePaste))

        assertEquals("java.lang.NullPointerException", r.exceptionClass) // 정규식 우선
        assertEquals("주문 금액 합산 중 NPE", r.title)
        assertEquals("쿠폰 null 로 합산 시 NPE", r.summary)
        assertEquals(Severity.S2, r.severity)
        assertEquals(listOf("npe", "kotlin", "order", "payment", "extra6"), r.tags)
        assertFalse(r.degraded)
    }

    @Test
    fun `잘못된 severity 문자열은 버려져 null 이 된다`() {
        val llm = FixedLlm(
            DraftExtractionPort.Output(
                title = "t", exceptionClass = null, summary = null,
                tags = emptyList(), severity = "CRITICAL", confidence = 0.5,
            ),
        )
        val r = useCase(llm).suggest(SuggestErrorDraftUseCase.Command(npePaste))
        assertNull(r.severity)
    }

    @Test
    fun `민감정보는 LLM 으로 보내기 전 마스킹된다`() {
        val llm = FixedLlm(null)
        val secretPaste = "Authorization: Bearer abcDEF123456\n$npePaste\ncontact user=alice@example.com"

        useCase(llm).suggest(SuggestErrorDraftUseCase.Command(secretPaste))

        val sent = llm.lastInput!!.maskedPaste
        assertFalse(sent.contains("abcDEF123456"), "토큰이 LLM 입력에 남아있음: $sent")
        assertFalse(sent.contains("alice@example.com"), "이메일이 LLM 입력에 남아있음: $sent")
    }
}
