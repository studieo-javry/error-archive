package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import java.time.LocalDateTime

@Entity
@Table(
    name = "error_case_comment",
    indexes = [
        Index(name = "ix_comment_case_created", columnList = "error_case_id, created_at"),
        Index(name = "ix_comment_parent", columnList = "parent_comment_id"),
        Index(name = "ix_comment_author", columnList = "author_user_id"),
    ]
)
class CommentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "error_case_id", nullable = false)
    var errorCaseId: Long,

    @Column(name = "author_user_id", nullable = false)
    var authorUserId: Long,

    /** depth=1 — top-level 이면 null, 답글이면 top-level 의 id. */
    @Column(name = "parent_comment_id")
    var parentCommentId: Long?,

    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    var body: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "quote_source_kind", nullable = false, length = 16)
    var quoteSourceKind: QuoteSourceKind,

    /** STEP → step row id / 그 외 null. */
    @Column(name = "quote_source_id")
    var quoteSourceId: Long?,

    /** snapshot 텍스트 — 도메인이 QUOTE_SNAPSHOT_MAX(=500) 로 잘라 저장. */
    @Column(name = "quote_snapshot", length = 500)
    var quoteSnapshot: String?,

    @Column(name = "quote_snapshot_truncated", nullable = false)
    var quoteSnapshotTruncated: Boolean = false,

    @Column(name = "edited_at")
    var editedAt: LocalDateTime?,

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime?,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
) {
    fun toDomain(): Comment = Comment.rehydrate(
        id = id!!,
        errorCaseId = errorCaseId,
        authorUserId = authorUserId,
        parentCommentId = parentCommentId,
        body = body,
        quoteSourceKind = quoteSourceKind,
        quoteSourceId = quoteSourceId,
        quoteSnapshot = quoteSnapshot,
        quoteSnapshotTruncated = quoteSnapshotTruncated,
        editedAt = editedAt,
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(c: Comment) = CommentEntity(
            id = c.id,
            errorCaseId = c.errorCaseId,
            authorUserId = c.authorUserId,
            parentCommentId = c.parentCommentId,
            body = c.body,
            quoteSourceKind = c.quoteSourceKind,
            quoteSourceId = c.quoteSourceId,
            quoteSnapshot = c.quoteSnapshot,
            quoteSnapshotTruncated = c.quoteSnapshotTruncated,
            editedAt = c.editedAt,
            deletedAt = c.deletedAt,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
        )
    }
}
