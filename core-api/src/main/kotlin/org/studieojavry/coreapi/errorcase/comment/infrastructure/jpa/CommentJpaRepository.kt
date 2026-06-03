package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentEntity

interface CommentJpaRepository : JpaRepository<CommentEntity, Long> {
    fun findAllByErrorCaseIdOrderByCreatedAtAscIdAsc(errorCaseId: Long): List<CommentEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from CommentEntity c where c.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int
}
