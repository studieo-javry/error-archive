package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import java.time.LocalDateTime

/** 목록 항목 요약 응답. */
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
    val occurredAt: LocalDateTime?
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
            occurredAt = s.occurredAt
        )
    }
}

/** 커서 기반 목록 응답. `nextCursor` 가 null 이면 끝. */
data class ErrorCaseListResponse(
    val items: List<ErrorCaseSummaryResponse>,
    val nextCursor: String?,
    val hasNext: Boolean
)
