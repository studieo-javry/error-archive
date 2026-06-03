package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentHelpfulEntity

interface CommentHelpfulJpaRepository : JpaRepository<CommentHelpfulEntity, Long> {
    fun findAllByCommentIdIn(commentIds: List<Long>): List<CommentHelpfulEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from CommentHelpfulEntity h where h.commentId = :cid and h.userId = :uid")
    fun deleteByCommentUser(@Param("cid") commentId: Long, @Param("uid") userId: Long): Int

    @Modifying(clearAutomatically = true)
    @Query("delete from CommentHelpfulEntity h where h.commentId = :cid")
    fun deleteByCommentId(@Param("cid") commentId: Long): Int
}