package org.studieojavry.coreapi.errorcase.comment.domain.model

import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import java.time.LocalDateTime

/**
 * 댓글 애그리거트 루트. depth=1 답글 / 인용(snapshot) / soft delete / 편집됨 표시.
 * 리액션/도움됨/멘션은 별도 도메인 객체로 분리(Comment 가 직접 참조 X — 별도 테이블).
 */
class Comment private constructor(
    val id: Long?,
    val errorCaseId: Long,
    val authorUserId: Long,
    /** depth=1 강제 — top-level 이면 null, 답글이면 *top-level 의 id*(서버가 redirect). */
    val parentCommentId: Long?,
    var body: String,
    val quoteSourceKind: QuoteSourceKind,
    /** STEP → step.id, 그 외 null. */
    val quoteSourceId: Long?,
    val quoteSnapshot: String?,
    /** snapshot 이 QUOTE_SNAPSHOT_MAX 에 잘렸는지. */
    val quoteSnapshotTruncated: Boolean,
    var editedAt: LocalDateTime?,
    var deletedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    var updatedAt: LocalDateTime
) {
    init {
        require(body.isNotBlank()) { "comment body must not be blank" }
        if (quoteSourceKind == QuoteSourceKind.NONE) {
            require(quoteSourceId == null) { "NONE quote must not have sourceId" }
            require(quoteSnapshot == null) { "NONE quote must not have snapshot" }
        }
    }

    /** body 수정 — 본인만(권한은 호출자가 검사). editedAt 세팅. */
    fun edit(newBody: String, at: LocalDateTime = LocalDateTime.now()) {
        require(newBody.isNotBlank()) { "comment body must not be blank" }
        if (body == newBody) return
        body = newBody
        editedAt = at
        updatedAt = at
    }

    /** soft delete. body 는 placeholder 로 비우고 deletedAt 세팅. 멱등. */
    fun softDelete(at: LocalDateTime = LocalDateTime.now()) {
        if (deletedAt != null) return
        body = DELETED_BODY_PLACEHOLDER
        deletedAt = at
        updatedAt = at
    }

    val isDeleted: Boolean get() = deletedAt != null
    val isTopLevel: Boolean get() = parentCommentId == null

    companion object {
        const val BODY_MAX = 5000
        const val QUOTE_SNAPSHOT_MAX = 500
        const val DELETED_BODY_PLACEHOLDER = "(삭제된 본문)"

        fun create(
            errorCaseId: Long,
            authorUserId: Long,
            parentCommentId: Long?,
            body: String,
            quoteSourceKind: QuoteSourceKind,
            quoteSourceId: Long?,
            quoteSnapshot: String?,
        ): Comment {
            require(body.length <= BODY_MAX) { "comment body must be $BODY_MAX chars or less" }
            val (truncSnapshot, truncated) = truncateSnapshot(quoteSnapshot)
            val now = LocalDateTime.now()
            return Comment(
                id = null,
                errorCaseId = errorCaseId,
                authorUserId = authorUserId,
                parentCommentId = parentCommentId,
                body = body,
                quoteSourceKind = quoteSourceKind,
                quoteSourceId = quoteSourceId,
                quoteSnapshot = truncSnapshot,
                quoteSnapshotTruncated = truncated,
                editedAt = null,
                deletedAt = null,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun rehydrate(
            id: Long, errorCaseId: Long, authorUserId: Long, parentCommentId: Long?,
            body: String, quoteSourceKind: QuoteSourceKind, quoteSourceId: Long?,
            quoteSnapshot: String?, quoteSnapshotTruncated: Boolean,
            editedAt: LocalDateTime?, deletedAt: LocalDateTime?,
            createdAt: LocalDateTime, updatedAt: LocalDateTime,
        ): Comment = Comment(
            id, errorCaseId, authorUserId, parentCommentId,
            body, quoteSourceKind, quoteSourceId,
            quoteSnapshot, quoteSnapshotTruncated,
            editedAt, deletedAt, createdAt, updatedAt
        )

        /** snapshot 을 QUOTE_SNAPSHOT_MAX 로 자른다. null/빈 문자열은 그대로. */
        fun truncateSnapshot(s: String?): Pair<String?, Boolean> {
            if (s == null) return null to false
            val trimmed = s.trim()
            if (trimmed.isEmpty()) return null to false
            return if (trimmed.length > QUOTE_SNAPSHOT_MAX) {
                trimmed.take(QUOTE_SNAPSHOT_MAX) to true
            } else trimmed to false
        }
    }
}
