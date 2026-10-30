package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.case.application.port.DraftExtractionPort
import org.studieojavry.coreapi.errorcase.case.application.port.DraftRateLimiterPort
import org.studieojavry.coreapi.errorcase.case.infrastructure.extractor.RegexErrorSnapshotExtractorAdapter

/**
 * 비용 가드가 UseCase 경로에 올바르게 물렸는지 — 리미터는 fake 로 주입해 결정적으로 검증.
 *  - 사용자 쿼터 초과 → 429 예외(LLM 호출 안 함)
 *  - 전역 예산 소진 → LLM 생략·degraded(정규식 폴백, 요청은 성공)
 *  - 아주 긴 한 줄 입력 → 문자수 상한으로 LLM 입력 절삭
 */
class DraftRateLimitUseCaseTest {

    private val regex = RegexErrorSnapshotExtractorAdapter()

    private class FakeLimiter(
        val userAllowed: Boolean = true,
        val retryAfter: Long = 30,
        val globalOk: Boolean = true,
    ) : DraftRateLimiterPort {
        override fun checkUser(userId: Long) =
            DraftRateLimiterPort.Decision(userAllowed, retryAfter, if (userAllowed) null else "per-minute")
        override fun tryConsumeGlobalBudget() = globalOk
    }

    private class CapturingLlm(private val out: DraftExtractionPort.Output?) : DraftExtractionPort {
        var calls = 0
        var lastInput: DraftExtractionPort.Input? = null
        override fun extract(input: DraftExtractionPort.Input): DraftExtractionPort.Output? {
            calls++; lastInput = input; return out
        }
    }

    private val goodOut = DraftExtractionPort.Output("제목", "java.lang.IllegalStateException", "요약", listOf("java"), "S2", 0.9)
    private fun uc(llm: DraftExtractionPort, limiter: DraftRateLimiterPort) =
        SuggestErrorDraftUseCase(regex, llm, limiter)
    private val paste = "java.lang.IllegalStateException: boom\n\tat a.B.c(B.kt:1)"

    @Test
    fun `사용자 쿼터 초과면 429 예외를 던지고 LLM 을 호출하지 않는다`() {
        val llm = CapturingLlm(goodOut)
        val ex = assertThrows(DraftRateLimitExceededException::class.java) {
            uc(llm, FakeLimiter(userAllowed = false, retryAfter = 42)).suggest(SuggestErrorDraftUseCase.Command(paste, userId = 7))
        }
        assertEquals(42, ex.retryAfterSeconds)
        assertEquals(0, llm.calls, "쿼터 초과 시 LLM 호출 금지")
    }

    @Test
    fun `전역 예산 소진이면 LLM 생략하고 degraded 로 폴백한다`() {
        val llm = CapturingLlm(goodOut)
        val r = uc(llm, FakeLimiter(globalOk = false)).suggest(SuggestErrorDraftUseCase.Command(paste, userId = 7))
        assertEquals(0, llm.calls, "예산 소진 시 LLM 호출 안 함")
        assertTrue(r.degraded, "LLM 생략 → degraded")
        assertEquals("java.lang.IllegalStateException", r.exceptionClass, "정규식 폴백은 동작")
    }

    @Test
    fun `정상 허용이면 LLM 을 호출하고 degraded 가 아니다`() {
        val llm = CapturingLlm(goodOut)
        val r = uc(llm, FakeLimiter()).suggest(SuggestErrorDraftUseCase.Command(paste, userId = 7))
        assertEquals(1, llm.calls)
        assertFalse(r.degraded)
    }

    @Test
    fun `거대한 한 줄 입력은 문자수 상한으로 절삭되어 LLM 입력이 짧아진다`() {
        val huge = "java.lang.IllegalStateException: " + "x".repeat(50_000) // 한 줄 → 라인 절삭 안 먹힘
        val llm = CapturingLlm(goodOut)
        uc(llm, FakeLimiter()).suggest(SuggestErrorDraftUseCase.Command(huge, userId = 7))
        val sent = llm.lastInput!!.maskedPaste
        assertTrue(sent.contains("chars trimmed"), "문자 절삭 마커가 있어야 함")
        assertTrue(sent.length <= 8100, "문자수 상한(8000) 수준으로 짧아져야 함: ${sent.length}")
    }
}
