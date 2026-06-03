package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentMention

@Entity
@Table(
    name = "error_case_comment_mention",
    indexes = [Index(name = "ix_comment_mention_comment", columnList = "comment_id")]
)
class CommentMentionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "comment_id", nullable = false)
    var commentId: Long,

    @Column(name = "mentioned_identifier", nullable = false, length = 100)
    var mentionedIdentifier: String,
) {
    fun toDomain(): CommentMention = CommentMention.rehydrate(id!!, commentId, mentionedIdentifier)
    companion object {
        fun fromDomain(m: CommentMention) = CommentMentionEntity(
            id = m.id, commentId = m.commentId, mentionedIdentifier = m.mentionedIdentifier,
        )
    }
}
