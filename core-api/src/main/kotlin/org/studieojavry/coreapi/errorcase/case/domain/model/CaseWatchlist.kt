package org.studieojavry.coreapi.errorcase.case.domain.model

import java.time.LocalDateTime

/**
 * Watchlist (즐겨찾기) — *향후* 케이스 활동을 받아보겠다는 명시적 follow.
 *
 * me-too 와 *완전 별개 도메인*:
 *  - me-too: 과거 경험 신호 (통계/카운트). 알림 trigger 아님.
 *  - watchlist: 향후 활동 알림 + home feed.
 *
 * 도메인 규칙:
 *  - UNIQUE (errorCaseId, userId) — 같은 사용자가 같은 케이스에 1번만.
 *  - 본인 케이스도 추가 가능 (스스로 활동 알림 받음 — 협업 흐름)
 *  - 토글: 누름 ↔ 해제 — POST 멱등, DELETE 멱등.
 *
 * 자동 등록 정책 (use case 단):
 *  - 댓글 작성 / step 추가 / solution 등록 시 작성자 자동 upsert.
 */
class CaseWatchlist private constructor(
    val id: Long?,
    val errorCaseId: Long,
    val userId: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun create(errorCaseId: Long, userId: Long) =
            CaseWatchlist(null, errorCaseId, userId, LocalDateTime.now())
        fun rehydrate(id: Long, errorCaseId: Long, userId: Long, createdAt: LocalDateTime) =
            CaseWatchlist(id, errorCaseId, userId, createdAt)
    }
}
