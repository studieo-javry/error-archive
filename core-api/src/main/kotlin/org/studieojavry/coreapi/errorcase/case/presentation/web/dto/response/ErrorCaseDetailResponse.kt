package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import java.time.LocalDateTime

/**
 * 에러케이스 상세 응답 — 스냅샷/스니펫/첨부 전체 포함.
 */
data class ErrorCaseDetailResponse(
    val id: Long,
    val ownerUserId: Long,
    val title: String,
    val scope: String?,
    val status: String,
    val visibility: String,
    val description: String?,
    val workspaceId: Long?,
    val severity: Int?,
    val environment: String?,
    val occurredAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val snapshot: SnapshotDto?,
    val snippets: List<SnippetDto>,
    val attachments: List<AttachmentDto>
) {
    data class SnapshotDto(
        val exceptionClass: String?,
        val exceptionMessage: String?,
        val rawStackTrace: String?,
        val fingerprint: String?,
        val rawPaste: String?
    )

    data class SnippetDto(
        val markerId: String,
        val title: String,
        val language: String,
        val filePathOrClass: String?,
        val lineRange: String?,
        val caption: String?,
        val code: String
    )

    data class AttachmentDto(
        val markerId: String,
        val fileName: String,
        val contentType: String,
        val size: Long,
        val kind: String,
        val storageUrl: String,
        val title: String?,
        val caption: String?
    )

    companion object {
        fun from(c: ErrorCase): ErrorCaseDetailResponse = ErrorCaseDetailResponse(
            id = requireNotNull(c.id),
            ownerUserId = c.ownerUserId,
            title = c.title,
            scope = c.scope,
            status = c.status.name,
            visibility = c.visibility.name,
            description = c.description,
            workspaceId = c.meta.workspaceId,
            severity = c.meta.severity?.code,
            environment = c.meta.environment,
            occurredAt = c.occurredAt,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
            snapshot = c.snapshot?.let {
                SnapshotDto(
                    exceptionClass = it.exceptionClass,
                    exceptionMessage = it.exceptionMessage,
                    rawStackTrace = it.rawStackTrace?.value,
                    fingerprint = it.fingeprint?.value,
                    rawPaste = it.rawPaste
                )
            },
            snippets = c.snippets.map {
                SnippetDto(
                    markerId = it.markerId,
                    title = it.title,
                    language = it.language,
                    filePathOrClass = it.filePathOrClass,
                    lineRange = it.lineRange,
                    caption = it.caption,
                    code = it.code
                )
            },
            attachments = c.attachments.map {
                AttachmentDto(
                    markerId = it.markerId,
                    fileName = it.fileName,
                    contentType = it.contentType,
                    size = it.size,
                    kind = it.kind.name,
                    storageUrl = it.storageUrl,
                    title = it.title,
                    caption = it.caption
                )
            }
        )
    }
}
