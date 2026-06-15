package org.studieojavry.coreapi.errorcase.case.application.port

/**
 * watchlist-feed 의 latest activity source. 4 종 활성:
 *  - CASE_RESOLVED         — case.resolvedAt 기반 virtual (JPA projection 없음). actor = case.resolvedByUserId.
 *  - COMMENT_POSTED        — comment table 의 row. actor = comment.authorUserId. (deleted 제외)
 *  - STEP_ADDED            — step table 의 row. actor = step.authorUserId.
 *  - SOLUTION_REGISTERED   — solution table 의 row. actor = solution.authorUserId.
 *
 * 후속 확장 포인트:
 *  - CASE_PUBLISHED        — publish-api 의 publishment 생성 시. publish-api 연동 완료 후 활성화.
 *  - CASE_REOPENED         — RESOLVED → IN_PROGRESS 등 재전환. case.reopenedAt 필드 도입 후 활성화.
 *  - CASE_UPDATED          — 제목/본문/severity 변경. 정책 결정 필요 (현재는 noise 로 판단해 제외).
 *
 * JPA projection 의 `source` 컬럼은 JPQL literal (`'COMMENT' as source`) 로 주입.
 */
enum class CaseActivitySource {
    CASE_RESOLVED,
    COMMENT_POSTED,
    STEP_ADDED,
    SOLUTION_REGISTERED,
    // TODO(post-MVP): publish-api 연동 완료 후 활성화
    // CASE_PUBLISHED,
}
