package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.CaseActivityProjection
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentEntity
import java.time.LocalDateTime

interface CommentJpaRepository : JpaRepository<CommentEntity, Long> {
    fun findAllByErrorCaseIdOrderByCreatedAtAscIdAsc(errorCaseId: Long): List<CommentEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from CommentEntity c where c.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int

    /** watchlist-feed unread 계산용 — globalSince 이후 활동을 case-id 별로 fetch. */
    @Query("""
        select c.errorCaseId as errorCaseId, c.id as activityId,
               c.createdAt as createdAt, c.authorUserId as authorUserId,
               'COMMENT' as source
          from CommentEntity c
         where c.errorCaseId in :caseIds
           and c.deletedAt is null
           and c.createdAt > :globalSince
    """)
    fun findActivitiesByCaseIdsSince(
        @Param("caseIds") caseIds: Collection<Long>,
        @Param("globalSince") globalSince: LocalDateTime,
    ): List<CaseActivityProjection>
}
