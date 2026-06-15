package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import java.time.LocalDateTime

/**
 * watchlist-feed 활동 fetch 공용 projection — 3 도메인 (Comment / Step / Solution) JPA query 의 공통 row.
 *
 * JPQL literal 로 `source` 컬럼 주입 — fan-in 후 어느 도메인 origin 인지 식별.
 * `source` 값은 `CaseActivitySource.name()` 과 일치하지 않음 (DB literal 은 짧게 — `'COMMENT'` / `'STEP'` / `'SOLUTION'`).
 * 매핑은 caller (`GetMyWatchlistFeedUseCase`) 에서.
 */
interface CaseActivityProjection {
    val errorCaseId: Long
    val createdAt: LocalDateTime
    val authorUserId: Long
    val source: String
}
