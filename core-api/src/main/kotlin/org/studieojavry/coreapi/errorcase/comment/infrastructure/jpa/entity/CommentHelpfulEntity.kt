package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentHelpful
import java.time.LocalDateTime

@Entity
@Table(
    name = "error_case_comment_helpful",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_comment_helpful_user",
            columnNames = ["comment_id", "user_id"]
        )
    ],
    indexes = [
        Index(name = "ix_comment_helpful_comment", columnList = "comment_id"),
    ]
)
class CommentHelpfulEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "comment_id", nullable = false)
    var commentId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime,
) {
    fun toDomain(): CommentHelpful = CommentHelpful.rehydrate(id!!, commentId, userId, createdAt)
    companion object {
        fun fromDomain(h: CommentHelpful) = CommentHelpfulEntity(
            id = h.id, commentId = h.commentId, userId = h.userId, createdAt = h.createdAt,
        )
    }
}
