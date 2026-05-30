package org.studieojavry.coreapi.errorcase.case.domain.model

import java.time.LocalDateTime

/**
 * "나도 겪었어요" — 에러 케이스에 *같은 경험* 을 표시한 사용자 1건.
 *
 * 도메인 규칙:
 *  - UNIQUE (errorCaseId, userId) — 같은 사용자가 같은 케이스에 1번만.
 *  - 본인 케이스에는 누를 수 없음 (use case 단에서 검증).
 *  - 토글: 누름 ↔ 해제 — POST 멱등(이미 있어도 200), DELETE 멱등(없어도 204).
 */
class CaseMeToo private constructor(
    val id: Long?,
    val errorCaseId: Long,
    val userId: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun create(errorCaseId: Long, userId: Long) =
            CaseMeToo(null, errorCaseId, userId, LocalDateTime.now())
        fun rehydrate(id: Long, errorCaseId: Long, userId: Long, createdAt: LocalDateTime) =
            CaseMeToo(id, errorCaseId, userId, createdAt)
    }
}
