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
 * 등록은 항상 사용자의 명시적 선택(opt-in) — 댓글/step/solution 작성이 자동으로
 * watchlist 에 편입시키지 않는다. 답글/멘션 알림(1회성)과 watchlist(지속 추적)는
 * 별개의 약속이라 사용자가 직접 골라야 한다는 판단 (2026-07-08 재검토, 자동 upsert 제거).
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
