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
import jakarta.persistence.UniqueConstraint
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentSuggestion
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.SuggestionSourceType
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.SuggestionStatus
import java.time.LocalDateTime

@Entity
@Table(
    name = "error_case_comment_suggestion",
    uniqueConstraints = [UniqueConstraint(name = "uq_comment_suggestion_comment", columnNames = ["comment_id"])],
    indexes = [
        Index(name = "ix_comment_suggestion_source", columnList = "source_type, source_id"),
        Index(name = "ix_comment_suggestion_status", columnList = "status"),
    ]
)
class CommentSuggestionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "comment_id", nullable = false)
    var commentId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    var sourceType: SuggestionSourceType,

    @Column(name = "source_id", nullable = false, length = 128)
    var sourceId: String,

    @Column(name = "start_line", nullable = false)
    var startLine: Int,

    @Column(name = "end_line", nullable = false)
    var endLine: Int,

    @Column(name = "old_code", columnDefinition = "TEXT", nullable = false)
    var oldCode: String,

    @Column(name = "new_code", columnDefinition = "TEXT", nullable = false)
    var newCode: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: SuggestionStatus,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
) {
    fun toDomain(): CommentSuggestion = CommentSuggestion.rehydrate(
        id = id!!, commentId = commentId,
        sourceType = sourceType, sourceId = sourceId,
        startLine = startLine, endLine = endLine,
        oldCode = oldCode, newCode = newCode,
        status = status,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(s: CommentSuggestion) = CommentSuggestionEntity(
            id = s.id, commentId = s.commentId,
            sourceType = s.sourceType, sourceId = s.sourceId,
            startLine = s.startLine, endLine = s.endLine,
            oldCode = s.oldCode, newCode = s.newCode,
            status = s.status,
            createdAt = s.createdAt, updatedAt = s.updatedAt,
        )
    }
}
