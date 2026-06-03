package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentMentionEntity

interface CommentMentionJpaRepository : JpaRepository<CommentMentionEntity, Long> {
    fun findAllByCommentIdIn(commentIds: List<Long>): List<CommentMentionEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from CommentMentionEntity m where m.commentId = :cid")
    fun deleteByCommentId(@Param("cid") commentId: Long): Int
}