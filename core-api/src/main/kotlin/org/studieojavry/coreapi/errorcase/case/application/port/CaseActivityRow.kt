package org.studieojavry.coreapi.errorcase.case.application.port

import java.time.LocalDateTime

/**
 * 홈 대시보드의 unread 우선 정렬 + watchlist-feed 의 latest activity 매핑용 — Comment/Step/Solution
 * 의 활동 row 를 batch 로 받아서 caller (UseCase) 가 case-id 별 lastViewedAt 비교 + author != me
 * 필터를 in-memory 로 적용한다.
 *
 * `source` 는 JPQL literal (`'COMMENT' as source`) 로 주입 — 3 도메인 row 가 fan-in 된 뒤에도
 * watchlist-feed 가 정확한 type/actor 매핑을 할 수 있게.
 *
 * 3 도메인 port 가 동일 row 타입을 반환하기 위한 공용 carrier.
 */
data class CaseActivityRow(
    val errorCaseId: Long,
    val createdAt: LocalDateTime,
    val authorUserId: Long,
    val source: CaseActivitySource,
)
