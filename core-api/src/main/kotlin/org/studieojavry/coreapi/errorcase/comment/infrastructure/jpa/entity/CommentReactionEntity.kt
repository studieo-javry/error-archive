package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentReaction
import java.time.LocalDateTime

@Entity
@Table(
    name = "error_case_comment_reaction",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_comment_reaction_user_emoji",
            columnNames = ["comment_id", "user_id", "emoji"]
        )
    ],
    indexes = [
        Index(name = "ix_comment_reaction_comment", columnList = "comment_id"),
    ]
)
class CommentReactionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "comment_id", nullable = false)
    var commentId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "emoji", nullable = false, length = 16)
    var emoji: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime,
) {
    fun toDomain(): CommentReaction = CommentReaction.rehydrate(id!!, commentId, userId, emoji, createdAt)
    companion object {
        fun fromDomain(r: CommentReaction) = CommentReactionEntity(
            id = r.id, commentId = r.commentId, userId = r.userId, emoji = r.emoji, createdAt = r.createdAt,
        )
    }
}
