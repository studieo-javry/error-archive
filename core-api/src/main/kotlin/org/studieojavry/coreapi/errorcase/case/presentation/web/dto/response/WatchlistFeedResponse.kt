package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.port.CaseActivitySource
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyWatchlistFeedUseCase
import java.time.LocalDateTime

/**
 * 홈 대시보드의 "Watchlist · 업데이트" 카드 1개.
 *
 * 사용자가 *watchlist 한* case 의 *최근 활동* 정보 + unread 카운트.
 * FE 는 latestActivityType + actor 를 "[user]가 X 했어요" 같은 verb 로 렌더.
 */
data class WatchlistFeedItemResponse(
    val id: Long,
    val title: String,
    val status: String,
    val visibility: String,
    val severity: Int?,
    val workspaceId: Long?,
    /** 마지막 활동 source — CASE_RESOLVED / COMMENT_POSTED / STEP_ADDED / SOLUTION_REGISTERED. CASE_PUBLISHED 는 publish-api 연동 후 추가. */
    val latestActivitySource: String,
    /** 활동 주체. source 별 정확 매핑 — CASE_RESOLVED → resolvedBy, 그 외 → 작성자. */
    val latestActivityActorUserId: Long?,
    val lastActivityAt: LocalDateTime,
    /** 사용자가 case 를 마지막으로 본 이후 다른 사람의 활동 수. */
    val unreadActivityCount: Long,
    /**
     * FE 가 카드 클릭 시 navigate 할 case 상세 path (relative) + **활동별 deep link fragment**.
     * Vue Router 의 `scrollBehavior` 가 hash 를 native 스크롤 처리 → FE 무수정 동작.
     *
     * - `COMMENT_POSTED` → `/error-cases/{id}#comment-{activityId}`
     * - `STEP_ADDED`    → `/error-cases/{id}#step-{activityId}`
     * - `SOLUTION_REGISTERED` → `/error-cases/{id}#solution-{activityId}`
     * - `CASE_RESOLVED` → `/error-cases/{id}` (virtual — anchor 없음)
     *
     * FE 는 case 상세 페이지 마크업에 `<div id="comment-{id}">` 같이 anchor 부여 필요.
     */
    val detailUrl: String,
) {
    companion object {
        fun from(i: GetMyWatchlistFeedUseCase.Item) = WatchlistFeedItemResponse(
            id = i.summary.id,
            title = i.summary.title,
            status = i.summary.status.name,
            visibility = i.summary.visibility.name,
            severity = i.summary.severityCode,
            workspaceId = i.summary.workspaceId,
            latestActivitySource = i.latestActivitySource.name,
            latestActivityActorUserId = i.latestActivityActorUserId,
            lastActivityAt = i.lastActivityAt,
            unreadActivityCount = i.unreadActivityCount,
            detailUrl = buildDetailUrl(i.summary.id, i.latestActivitySource, i.latestActivityId),
        )

        private fun buildDetailUrl(caseId: Long, source: CaseActivitySource, activityId: Long?): String {
            val base = "/error-cases/$caseId"
            if (activityId == null) return base  // CASE_RESOLVED 등 virtual activity
            val anchor = when (source) {
                CaseActivitySource.COMMENT_POSTED -> "comment"
                CaseActivitySource.STEP_ADDED -> "step"
                CaseActivitySource.SOLUTION_REGISTERED -> "solution"
                CaseActivitySource.CASE_RESOLVED -> return base
            }
            return "$base#$anchor-$activityId"
        }
    }
}

data class WatchlistFeedResponse(
    val items: List<WatchlistFeedItemResponse>,
)
