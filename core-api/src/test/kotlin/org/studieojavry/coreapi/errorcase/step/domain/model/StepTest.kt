package org.studieojavry.coreapi.errorcase.step.domain.model

import org.junit.jupiter.api.assertThrows
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Step 도메인 — 부분 수정(null=유지) + attemptType 명시적 clear + 불변식. 순수 도메인.
 */
class StepTest {

    private fun newStep(attemptType: String? = "MANUAL") = Step.create(
        errorCaseId = 1L, authorUserId = 7L, orderIndex = 0,
        title = "재현", status = StepStatus.IN_PROGRESS,
        attemptType = attemptType, body = "본문", insight = "요약",
    )

    @Test
    fun `update 는 제공된 필드만 바꾸고 나머지는 유지한다`() {
        val s = newStep()
        s.update(title = "새 제목")
        assertEquals("새 제목", s.title)
        assertEquals("본문", s.body)                 // 유지
        assertEquals(StepStatus.IN_PROGRESS, s.status) // 유지
        assertEquals("MANUAL", s.attemptType)          // 유지
    }

    @Test
    fun `update status 전이`() {
        val s = newStep()
        s.update(status = StepStatus.RESOLVED)
        assertEquals(StepStatus.RESOLVED, s.status)
    }

    @Test
    fun `clearAttemptType 는 attemptType 을 비운다`() {
        val s = newStep()
        s.update(clearAttemptType = true)
        assertNull(s.attemptType)
    }

    @Test
    fun `attemptType null 은 변경 없음 (clear 아님)`() {
        val s = newStep(attemptType = "MANUAL")
        s.update(attemptType = null)
        assertEquals("MANUAL", s.attemptType)
    }

    @Test
    fun `update 는 빈 title 을 거부한다`() {
        val s = newStep()
        assertThrows<IllegalArgumentException> { s.update(title = "   ") }
    }

    @Test
    fun `create 는 200자 초과 title 을 거부한다`() {
        assertThrows<IllegalArgumentException> {
            Step.create(1L, 7L, 0, "a".repeat(201), StepStatus.IN_PROGRESS, null, null, null)
        }
    }

    @Test
    fun `create 는 500자 초과 insight 를 거부한다`() {
        assertThrows<IllegalArgumentException> {
            Step.create(1L, 7L, 0, "ok", StepStatus.IN_PROGRESS, null, null, "a".repeat(501))
        }
    }
}
