package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "중복 가능성 높음"(likelyDuplicate) 판정 규칙 단위 테스트.
 * 규칙: score ≥ 0.85 AND 입력·후보 제목의 예외 클래스(`*Exception`/`*Error`)가 하나라도 겹침.
 */
class DuplicateHintTest {

    @Test
    fun `예외 클래스 토큰을 대소문자 무관하게 추출`() {
        assertEquals(setOf("nullpointerexception"), DuplicateHint.exceptionClasses("NullPointerException in OrderService"))
        assertEquals(setOf("outofmemoryerror"), DuplicateHint.exceptionClasses("PDF 내보내기 중 OutOfMemoryError"))
        // 사용자가 소문자로 타이핑해도 매칭
        assertEquals(setOf("nullpointerexception"), DuplicateHint.exceptionClasses("nullpointerexception 발생"))
        // 예외 토큰이 없으면 빈 집합
        assertTrue(DuplicateHint.exceptionClasses("커넥션 풀 고갈로 503").isEmpty())
        // 단독 'error' 단어는 예외 클래스가 아님(접두사 필요)
        assertTrue(DuplicateHint.exceptionClasses("error 발생").isEmpty())
    }

    @Test
    fun `점수 임계 이상 + 예외 클래스 동일이면 중복`() {
        val q = DuplicateHint.exceptionClasses("NullPointerException 결제")
        assertTrue(DuplicateHint.isLikelyDuplicate(0.85, q, "NullPointerException in PaymentService"))
        assertTrue(DuplicateHint.isLikelyDuplicate(0.91, q, "결제 중 nullpointerexception 재발"))
    }

    @Test
    fun `점수 임계 미만이면 예외 클래스 같아도 중복 아님`() {
        val q = DuplicateHint.exceptionClasses("NullPointerException 결제")
        assertFalse(DuplicateHint.isLikelyDuplicate(0.84, q, "NullPointerException in PaymentService"))
        assertFalse(DuplicateHint.isLikelyDuplicate(0.70, q, "NullPointerException in PaymentService"))
    }

    @Test
    fun `점수 높아도 예외 클래스 다르면 중복 아님`() {
        val q = DuplicateHint.exceptionClasses("NullPointerException 결제")
        assertFalse(DuplicateHint.isLikelyDuplicate(0.95, q, "OutOfMemoryError in PaymentService"))
    }

    @Test
    fun `입력에 예외 클래스가 없으면 중복 아님`() {
        val q = DuplicateHint.exceptionClasses("결제 중 간헐적 503")
        assertFalse(DuplicateHint.isLikelyDuplicate(0.99, q, "NullPointerException in PaymentService"))
    }
}
