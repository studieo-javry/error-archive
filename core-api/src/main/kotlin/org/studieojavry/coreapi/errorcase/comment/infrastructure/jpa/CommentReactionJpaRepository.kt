package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentReactionEntity

interface CommentReactionJpaRepository : JpaRepository<CommentReactionEntity, Long> {
    fun findAllByCommentIdIn(commentIds: List<Long>): List<CommentReactionEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from CommentReactionEntity r where r.commentId = :cid and r.userId = :uid and r.emoji = :emoji")
    fun deleteByCommentUserEmoji(
        @Param("cid") commentId: Long,
        @Param("uid") userId: Long,
        @Param("emoji") emoji: String,
    ): Int

    @Modifying(clearAutomatically = true)
    @Query("delete from CommentReactionEntity r where r.commentId = :cid")
    fun deleteByCommentId(@Param("cid") commentId: Long): Int
}