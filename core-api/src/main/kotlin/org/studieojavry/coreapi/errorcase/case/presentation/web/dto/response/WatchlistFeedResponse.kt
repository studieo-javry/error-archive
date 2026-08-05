package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort.AuthorSummary
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
    val workspaceId: Long?,
    /** 마지막 활동 source — CASE_RESOLVED / COMMENT_POSTED / STEP_ADDED / SOLUTION_REGISTERED. CASE_PUBLISHED 는 publish-api 연동 후 추가. */
    val latestActivitySource: String,
    /** 활동 주체 userId. source 별 정확 매핑 — CASE_RESOLVED → resolvedBy, 그 외 → 작성자. (하위호환 유지) */
    val latestActivityActorUserId: Long?,
    /**
     * 활동 주체 프로필 (hydration). 비활성/삭제 사용자거나 actorId null 이면 null.
     * FE 는 이걸로 "[displayName]가 X 했어요" + 아바타 렌더. 없으면 `latestActivityActorUserId` fallback.
     */
    val actor: WatchlistFeedActorResponse?,
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
        fun from(
            i: GetMyWatchlistFeedUseCase.Item,
            authors: Map<Long, AuthorSummary>,
        ) = WatchlistFeedItemResponse(
            id = i.summary.id,
            title = i.summary.title,
            status = i.summary.status.name,
            visibility = i.summary.visibility.name,
            workspaceId = i.summary.workspaceId,
            latestActivitySource = i.latestActivitySource.name,
            latestActivityActorUserId = i.latestActivityActorUserId,
            actor = i.latestActivityActorUserId?.let { authors[it] }?.let { WatchlistFeedActorResponse.from(it) },
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

/** watchlist-feed 카드의 활동 주체 프로필 — 이름/handle/아바타 (bio 는 이 위젯에 불필요해 제외). */
data class WatchlistFeedActorResponse(
    val userId: Long,
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
) {
    companion object {
        fun from(a: AuthorSummary) = WatchlistFeedActorResponse(
            userId = a.userId,
            handle = a.handle,
            displayName = a.displayName,
            avatarUrl = a.avatarUrl,
        )
    }
}

data class WatchlistFeedResponse(
    val items: List<WatchlistFeedItemResponse>,
)
