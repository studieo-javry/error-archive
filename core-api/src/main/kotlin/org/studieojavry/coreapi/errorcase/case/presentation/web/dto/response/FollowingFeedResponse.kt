package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort.AuthorSummary
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyFollowingFeedUseCase
import java.time.LocalDateTime

/**
 * 홈 대시보드의 "Following · 새 케이스" 응답 — 팔로우한 사용자들의 최근 PUBLIC 케이스.
 *
 * *slim DTO* — feed 카드 렌더에 필요한 필드만.
 * Library 목록 조회의 `ErrorCaseSummaryResponse` 와 의도적으로 분리.
 * 미포함: `visibility` (항상 PUBLIC 이라 무의미), `workspaceId` (필터 UI 없음),
 * `fingerprint`, `exceptionClass` (dedup / 대표 클래스 뱃지는 Library UX), `occurredAt` (feed 는 케이스 등록 관점),
 * `descriptionPreview` (본문 미리보기 X, 제목 + tags 로 판단).
 */
data class FollowingFeedResponse(
    val items: List<FollowingFeedItemResponse>,
) {
    companion object {
        fun from(result: GetMyFollowingFeedUseCase.Result) = FollowingFeedResponse(
            items = result.items.map { summary ->
                FollowingFeedItemResponse.from(summary, result.authors[summary.ownerUserId])
            }
        )
    }
}

/** 팔로잉 피드 카드 1개. */
data class FollowingFeedItemResponse(
    val id: Long,
    val ownerUserId: Long,
    val title: String,
    val status: String,
    val createdAt: LocalDateTime,
    val tags: List<String>,
    val author: FollowingFeedAuthorResponse?,
) {
    companion object {
        fun from(s: ErrorCaseSummary, author: AuthorSummary?) = FollowingFeedItemResponse(
            id = s.id,
            ownerUserId = s.ownerUserId,
            title = s.title,
            status = s.status.name,
            createdAt = s.createdAt,
            tags = s.tags,
            author = author?.let { FollowingFeedAuthorResponse.from(it) },
        )
    }
}

/**
 * following feed 전용 작성자 칩 — `AuthorSummaryResponse` 에서 `isFollowing` 제외.
 *
 * 피드 항목은 정의상 *내가 팔로우한 사람* 의 케이스라 `isFollowing` 은 항상 true → 무의미.
 * 그래서 응답에서 빼고, use case 도 iam 에 `viewerUserId=null` 로 요청해 follow-check 쿼리 자체를 생략한다.
 * (검색/목록·suggested-followees 는 `isFollowing` 이 Follow 버튼 상태로 의미가 있어 `AuthorSummaryResponse` 유지.)
 */
data class FollowingFeedAuthorResponse(
    val userId: Long,
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
) {
    companion object {
        fun from(a: AuthorSummary) = FollowingFeedAuthorResponse(
            userId = a.userId,
            handle = a.handle,
            displayName = a.displayName,
            avatarUrl = a.avatarUrl,
            bio = a.bio,
        )
    }
}
