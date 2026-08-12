package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.usecase.DescriptionPreview
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyWatchlistUseCase
import java.time.LocalDateTime

/**
 * Library > Watchlist 페이지 · 관리용 목록 항목.
 *
 * `ErrorCaseSummaryResponse` 의 case-level 필드 + watchlist metadata (addedAt / lastActivityAt / unreadCount / isOwn).
 *
 * `isOwn` = case.ownerUserId == viewer — mock 의 MINE 배지용 (자동 포함, 명시 제거 불가 UI 힌트).
 */
data class WatchlistItemResponse(
    // case-level (ErrorCaseSummary 반영)
    val id: Long,
    val ownerUserId: Long,
    val title: String,
    val status: String,
    val visibility: String,
    val workspaceId: Long?,
    val exceptionClass: String?,
    val createdAt: LocalDateTime,
    val tags: List<String>,
    val descriptionPreview: String?,
    // watchlist-level
    val addedAt: LocalDateTime,
    val lastActivityAt: LocalDateTime?,
    val unreadCount: Long,
    val isOwn: Boolean,
) {
    companion object {
        fun from(item: GetMyWatchlistUseCase.Item, viewerUserId: Long): WatchlistItemResponse {
            val s = item.summary
            return WatchlistItemResponse(
                id = s.id,
                ownerUserId = s.ownerUserId,
                title = s.title,
                status = s.status.name,
                visibility = s.visibility.name,
                workspaceId = s.workspaceId,
                exceptionClass = s.exceptionClass,
                createdAt = s.createdAt,
                tags = s.tags,
                descriptionPreview = DescriptionPreview.of(s.descriptionRaw),
                addedAt = item.addedAt,
                lastActivityAt = item.lastActivityAt,
                unreadCount = item.unreadCount,
                isOwn = s.ownerUserId == viewerUserId,
            )
        }
    }
}

/**
 * Cursor pagination 응답. `nextCursor` 가 null 이면 마지막 페이지.
 * Cursor 는 base64 encoded offset (내부 구현) — 클라이언트는 opaque 로 처리.
 *
 * `totalCount` = 필터 적용 후 전체 건수("N개 중 X개" 표시용).
 * `statusCounts` = status별 건수(status 필터 제외, search·unread 반영) — 필터 칩 카운트용.
 *   전 status(OPEN/IN_PROGRESS/RESOLVED)를 0 포함으로 항상 반환.
 */
data class WatchlistListResponse(
    val items: List<WatchlistItemResponse>,
    val nextCursor: String?,
    val hasNext: Boolean,
    val totalCount: Long,
    val statusCounts: Map<String, Long>,
)
