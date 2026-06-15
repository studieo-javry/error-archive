package org.studieojavry.coreapi.errorcase.step.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.CaseActivityProjection
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.entity.StepEntity
import java.time.LocalDateTime

interface StepJpaRepository : JpaRepository<StepEntity, Long> {
    fun findAllByErrorCaseIdOrderByOrderIndexAscIdAsc(errorCaseId: Long): List<StepEntity>
    fun countByErrorCaseId(errorCaseId: Long): Long
    fun existsByErrorCaseIdAndStatus(errorCaseId: Long, status: StepStatus): Boolean

    @Modifying(clearAutomatically = true)
    @Query("delete from StepEntity s where s.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int

    /** watchlist-feed unread 계산용 — globalSince 이후 활동을 case-id 별로 fetch. */
    @Query("""
        select s.errorCaseId as errorCaseId, s.id as activityId,
               s.createdAt as createdAt, s.authorUserId as authorUserId,
               'STEP' as source
          from StepEntity s
         where s.errorCaseId in :caseIds
           and s.createdAt > :globalSince
    """)
    fun findActivitiesByCaseIdsSince(
        @Param("caseIds") caseIds: Collection<Long>,
        @Param("globalSince") globalSince: LocalDateTime,
    ): List<CaseActivityProjection>
}
