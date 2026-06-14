package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort.AuthorSummary
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.application.usecase.DescriptionPreview
import java.time.LocalDateTime

/**
 * 목록 항목 요약 응답.
 *
 * `tags` 는 케이스에 붙은 태그 전체 (정렬 createdAt asc). 최대 20개.
 * `descriptionPreview` 는 본문에서 마커(`@snippet(..)` / `@attach(..)`) 를 `[code]` / `[file]` 로 치환한 뒤
 *     공백 정규화 + max 120자 + 잘리면 끝에 `…` — 상세 본문이 아님.
 * `author` 는 검색 응답에만 hydration — 일반 목록 응답에서는 null 일 수 있음.
 */
data class ErrorCaseSummaryResponse(
    val id: Long,
    val ownerUserId: Long,
    val title: String,
    val status: String,
    val visibility: String,
    val severity: Int?,
    val workspaceId: Long?,
    val fingerprint: String?,
    val exceptionClass: String?,
    val createdAt: LocalDateTime,
    val occurredAt: LocalDateTime?,
    val tags: List<String>,
    val descriptionPreview: String?,
    val author: AuthorSummaryResponse?,
) {
    companion object {
        /** author 정보 없이 (일반 목록 응답용). */
        fun from(s: ErrorCaseSummary) = from(s, author = null)

        /** author hydration 포함 (검색 응답용). */
        fun from(s: ErrorCaseSummary, author: AuthorSummary?) = ErrorCaseSummaryResponse(
            id = s.id,
            ownerUserId = s.ownerUserId,
            title = s.title,
            status = s.status.name,
            visibility = s.visibility.name,
            severity = s.severityCode,
            workspaceId = s.workspaceId,
            fingerprint = s.fingerprint,
            exceptionClass = s.exceptionClass,
            createdAt = s.createdAt,
            occurredAt = s.occurredAt,
            tags = s.tags,
            descriptionPreview = DescriptionPreview.of(s.descriptionRaw),
            author = author?.let { AuthorSummaryResponse.from(it) },
        )
    }
}

/** 작성자 칩 + popover 렌더링용. */
data class AuthorSummaryResponse(
    val userId: Long,
    /** GitHub login 매핑된 unique handle. MVP 정책: 불변. */
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val isFollowing: Boolean,
) {
    companion object {
        fun from(a: AuthorSummary) = AuthorSummaryResponse(
            userId = a.userId,
            handle = a.handle,
            displayName = a.displayName,
            avatarUrl = a.avatarUrl,
            bio = a.bio,
            isFollowing = a.isFollowing,
        )
    }
}

/** 커서 기반 목록 응답. `nextCursor` 가 null 이면 끝. */
data class ErrorCaseListResponse(
    val items: List<ErrorCaseSummaryResponse>,
    val nextCursor: String?,
    val hasNext: Boolean
)
