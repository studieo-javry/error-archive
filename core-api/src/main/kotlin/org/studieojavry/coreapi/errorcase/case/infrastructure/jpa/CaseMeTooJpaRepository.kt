package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseMeTooEntity
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CaseCountProjection
import java.time.LocalDateTime

interface CaseMeTooJpaRepository : JpaRepository<CaseMeTooEntity, Long> {

    fun findByErrorCaseIdAndUserId(errorCaseId: Long, userId: Long): CaseMeTooEntity?

    fun findAllByErrorCaseIdOrderByCreatedAtAscIdAsc(errorCaseId: Long): List<CaseMeTooEntity>

    fun countByErrorCaseId(errorCaseId: Long): Long

    @Modifying(clearAutomatically = true)
    @Query("delete from CaseMeTooEntity m where m.errorCaseId = :caseId and m.userId = :userId")
    fun deleteByErrorCaseIdAndUserId(@Param("caseId") errorCaseId: Long, @Param("userId") userId: Long): Int

    @Modifying(clearAutomatically = true)
    @Query("delete from CaseMeTooEntity m where m.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int

    @Query("""
        select m.errorCaseId as errorCaseId, count(m) as count
          from CaseMeTooEntity m
         where m.errorCaseId in :caseIds
         group by m.errorCaseId
    """)
    fun countByCaseIdsGrouped(
        @Param("caseIds") caseIds: Collection<Long>,
    ): List<CaseCountProjection>

    @Query("""
        select m.errorCaseId as errorCaseId, count(m) as count
          from CaseMeTooEntity m
         where m.errorCaseId in :caseIds
           and m.createdAt > :since
         group by m.errorCaseId
    """)
    fun countByCaseIdsSinceGrouped(
        @Param("caseIds") caseIds: Collection<Long>,
        @Param("since") since: LocalDateTime,
    ): List<CaseCountProjection>

    @Query("""
        select m2.userId as userId, count(distinct m2.errorCaseId) as count
          from CaseMeTooEntity m2
         where m2.errorCaseId in (
             select m1.errorCaseId from CaseMeTooEntity m1 where m1.userId = :userId
         )
           and m2.userId <> :userId
         group by m2.userId
         order by count(distinct m2.errorCaseId) desc
    """)
    fun findCoOccurringUserIds(
        @Param("userId") userId: Long,
        pageable: org.springframework.data.domain.Pageable,
    ): List<UserCountProjection>
}

interface UserCountProjection {
    val userId: Long
    val count: Long
}
