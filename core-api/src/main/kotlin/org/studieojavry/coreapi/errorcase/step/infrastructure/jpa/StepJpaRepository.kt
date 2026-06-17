package org.studieojavry.coreapi.errorcase.step.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CaseActivityProjection
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CaseCountProjection
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CaseDateProjection
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

    // ── Recent Activity ───────────────────────────────────────
    @Query("""
        select s from StepEntity s
         where s.authorUserId = :authorUserId
           and s.createdAt >= :since
         order by s.createdAt desc, s.id desc
    """)
    fun findRecentByAuthor(
        @Param("authorUserId") authorUserId: Long,
        @Param("since") since: LocalDateTime,
        pageable: Pageable,
    ): List<StepEntity>

    @Query("""
        select s.errorCaseId as errorCaseId, max(s.createdAt) as value
          from StepEntity s
         where s.errorCaseId in :caseIds
         group by s.errorCaseId
    """)
    fun findMaxCreatedAtGrouped(
        @Param("caseIds") caseIds: Collection<Long>,
    ): List<CaseDateProjection>

    @Query("""
        select s.errorCaseId as errorCaseId, count(s) as count
          from StepEntity s
         where s.errorCaseId in :caseIds
         group by s.errorCaseId
    """)
    fun countGrouped(
        @Param("caseIds") caseIds: Collection<Long>,
    ): List<CaseCountProjection>

    @Query("""
        select count(s) from StepEntity s
         where s.errorCaseId = :errorCaseId
           and s.createdAt > :since
           and s.authorUserId <> :excludeUserId
    """)
    fun countSinceExcludingAuthor(
        @Param("errorCaseId") errorCaseId: Long,
        @Param("since") since: LocalDateTime,
        @Param("excludeUserId") excludeUserId: Long,
    ): Long

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

    @Query("""
        select count(s) from StepEntity s
         where s.authorUserId = :authorUserId
           and s.createdAt >= :since
    """)
    fun countByAuthorSince(
        @Param("authorUserId") authorUserId: Long,
        @Param("since") since: LocalDateTime,
    ): Long
}
