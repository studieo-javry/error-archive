package org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CaseActivityProjection
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CaseCountProjection
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CaseDateProjection
import org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa.entity.SolutionEntity
import java.time.LocalDateTime

interface SolutionJpaRepository : JpaRepository<SolutionEntity, Long> {
    fun findAllByErrorCaseIdOrderByCreatedAtAsc(errorCaseId: Long): List<SolutionEntity>

    /** stepId 가 step_id 컬럼에 포함된 모든 solution 반환. join via @ElementCollection 의 collection table. */
    @Query(
        """
        select s from SolutionEntity s join s.stepIds sid
         where sid = :stepId
        """
    )
    fun findReferencingStep(@Param("stepId") stepId: Long): List<SolutionEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from SolutionEntity s where s.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int

    // ── Recent Activity ───────────────────────────────────────
    @Query("""
        select s from SolutionEntity s
         where s.authorUserId = :authorUserId
           and s.createdAt >= :since
         order by s.createdAt desc, s.id desc
    """)
    fun findRecentByAuthor(
        @Param("authorUserId") authorUserId: Long,
        @Param("since") since: LocalDateTime,
        pageable: Pageable,
    ): List<SolutionEntity>

    @Query("""
        select s.errorCaseId as errorCaseId, max(s.createdAt) as value
          from SolutionEntity s
         where s.errorCaseId in :caseIds
         group by s.errorCaseId
    """)
    fun findMaxCreatedAtGrouped(
        @Param("caseIds") caseIds: Collection<Long>,
    ): List<CaseDateProjection>

    @Query("""
        select s.errorCaseId as errorCaseId, count(s) as count
          from SolutionEntity s
         where s.errorCaseId in :caseIds
         group by s.errorCaseId
    """)
    fun countGrouped(
        @Param("caseIds") caseIds: Collection<Long>,
    ): List<CaseCountProjection>

    @Query("""
        select count(s) from SolutionEntity s
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
               'SOLUTION' as source
          from SolutionEntity s
         where s.errorCaseId in :caseIds
           and s.createdAt > :globalSince
    """)
    fun findActivitiesByCaseIdsSince(
        @Param("caseIds") caseIds: Collection<Long>,
        @Param("globalSince") globalSince: LocalDateTime,
    ): List<CaseActivityProjection>

    @Query("""
        select count(s) from SolutionEntity s
         where s.authorUserId = :authorUserId
           and s.createdAt >= :since
    """)
    fun countByAuthorSince(
        @Param("authorUserId") authorUserId: Long,
        @Param("since") since: LocalDateTime,
    ): Long
}