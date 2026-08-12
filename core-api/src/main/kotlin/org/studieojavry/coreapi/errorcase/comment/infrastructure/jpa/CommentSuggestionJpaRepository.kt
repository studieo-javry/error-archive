package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentSuggestionEntity

interface CommentSuggestionJpaRepository : JpaRepository<CommentSuggestionEntity, Long> {

    fun findByCommentId(commentId: Long): CommentSuggestionEntity?

    fun findAllByCommentIdIn(commentIds: Collection<Long>): List<CommentSuggestionEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from CommentSuggestionEntity s where s.commentId = :commentId")
    fun deleteByCommentId(@Param("commentId") commentId: Long): Int

    @Modifying(clearAutomatically = true)
    @Query("delete from CommentSuggestionEntity s where s.commentId in :commentIds")
    fun deleteByCommentIdIn(@Param("commentIds") commentIds: Collection<Long>): Int
}
