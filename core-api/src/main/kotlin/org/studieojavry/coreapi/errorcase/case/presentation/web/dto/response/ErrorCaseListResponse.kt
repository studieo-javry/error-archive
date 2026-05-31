package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.application.usecase.DescriptionPreview
import java.time.LocalDateTime

/**
 * 목록 항목 요약 응답.
 *
 * `tags` 는 케이스에 붙은 태그 전체 (정렬 createdAt asc). 최대 20개.
 * `descriptionPreview` 는 본문에서 마커(`@snippet(..)` / `@attach(..)`) 를 `[code]` / `[file]` 로 치환한 뒤
 *     공백 정규화 + max 120자 + 잘리면 끝에 `…` — 상세 본문이 아님.
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
) {
    companion object {
        fun from(s: ErrorCaseSummary) = ErrorCaseSummaryResponse(
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
        )
    }
}

/** 커서 기반 목록 응답. `nextCursor` 가 null 이면 끝. */
data class ErrorCaseListResponse(
    val items: List<ErrorCaseSummaryResponse>,
    val nextCursor: String?,
    val hasNext: Boolean
)
