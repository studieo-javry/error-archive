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
    val project: String?,
    val status: String,
    val visibility: String,
    val description: String?,
    val workspaceId: Long?,
    val tags: List<String>,
    val occurredAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val snapshot: SnapshotDto?,
    val snippets: List<SnippetDto>,
    val attachments: List<AttachmentDto>,
    val meToo: MeTooDto,
    /** viewer 본인이 이 케이스를 watchlist 에 담고 있는지. FE 별(Watch) 상태 초기화용. */
    val watchedByMe: Boolean,
) {
    /** "나도 겪었어요" — count + viewer 본인의 표시 여부 + 누른 사용자 ID 목록(최신 30명). */
    data class MeTooDto(
        val count: Long,
        val taggedByMe: Boolean,
        val userIds: List<Long>,
    )
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
        val code: String,
        /**
         * 본문(description)에 `@snippet(markerId)` 토큰이 있어 인라인 배치되는지 여부.
         * false 면 FE 가 "관련 코드" 섹션에 렌더(첨부 inlineReferenced 와 대칭).
         */
        val inlineReferenced: Boolean,
    )

    data class AttachmentDto(
        val markerId: String,
        val fileName: String,
        val contentType: String,
        val size: Long,
        val kind: String,
        /** presigned inline URL — FE 가 `@attach(markerId)` 를 `<img src=url>` 로 치환. */
        val url: String,
        /** presigned attachment(다운로드) URL. */
        val downloadUrl: String,
        val title: String?,
        val caption: String?,
        /**
         * 본문(description)에 `@attach(markerId)` 토큰이 있어 인라인 배치되는지 여부.
         * false 면 FE 가 "관련 첨부" 갤러리 섹션에 렌더한다(태그=배치, 업로드=보관 정책).
         */
        val inlineReferenced: Boolean,
    )

    /** objectKey → (inline URL, download URL). 가시성별 TTL 반영은 호출측 책임. */
    fun interface AttachmentUrlResolver {
        fun resolve(objectKey: String): Pair<String, String>
    }

    companion object {
        fun from(
            c: ErrorCase,
            meToo: MeTooDto = MeTooDto(0, false, emptyList()),
            attachmentUrls: AttachmentUrlResolver,
            watchedByMe: Boolean = false,
        ): ErrorCaseDetailResponse = ErrorCaseDetailResponse(
            id = requireNotNull(c.id),
            ownerUserId = c.ownerUserId,
            title = c.title,
            project = c.project,
            status = c.status.name,
            visibility = c.visibility.name,
            description = c.description,
            workspaceId = c.meta.workspaceId,
            tags = c.tags.toList(),
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
                    code = it.code,
                    // 본문에 @snippet(markerId) 가 있으면 인라인, 없으면 "관련 코드" 섹션.
                    inlineReferenced = c.description?.contains(it.embedToken()) == true,
                )
            },
            attachments = c.attachments.map {
                val (url, downloadUrl) = attachmentUrls.resolve(it.objectKey)
                AttachmentDto(
                    markerId = it.markerId,
                    fileName = it.fileName,
                    contentType = it.contentType,
                    size = it.size,
                    kind = it.kind.name,
                    url = url,
                    downloadUrl = downloadUrl,
                    title = it.title,
                    caption = it.caption,
                    // 본문에 @attach(markerId) 가 있으면 인라인, 없으면 "관련 첨부" 갤러리.
                    inlineReferenced = c.description?.contains(it.embedToken()) == true,
                )
            },
            meToo = meToo,
            watchedByMe = watchedByMe,
        )
    }
}
