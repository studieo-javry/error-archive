package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

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
        )
    }
}

data class WatchlistFeedResponse(
    val items: List<WatchlistFeedItemResponse>,
)
