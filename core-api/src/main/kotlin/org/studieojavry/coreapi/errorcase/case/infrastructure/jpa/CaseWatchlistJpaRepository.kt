package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseWatchlistEntity

interface CaseWatchlistJpaRepository : JpaRepository<CaseWatchlistEntity, Long> {

    fun findByErrorCaseIdAndUserId(errorCaseId: Long, userId: Long): CaseWatchlistEntity?

    @Query("select w.userId from CaseWatchlistEntity w where w.errorCaseId = :caseId")
    fun findUserIdsByErrorCaseId(@Param("caseId") errorCaseId: Long): List<Long>

    @Query("""
        select w.errorCaseId from CaseWatchlistEntity w
         where w.userId = :userId
         order by w.createdAt desc, w.id desc
    """)
    fun findCaseIdsByUserId(@Param("userId") userId: Long, pageable: Pageable): List<Long>

    @Modifying(clearAutomatically = true)
    @Query("delete from CaseWatchlistEntity w where w.errorCaseId = :caseId and w.userId = :userId")
    fun deleteByErrorCaseIdAndUserId(@Param("caseId") errorCaseId: Long, @Param("userId") userId: Long): Int

    @Modifying(clearAutomatically = true)
    @Query("delete from CaseWatchlistEntity w where w.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int

    @Query("""
        select w2.userId as userId, count(distinct w2.errorCaseId) as count
          from CaseWatchlistEntity w2
         where w2.errorCaseId in (
             select w1.errorCaseId from CaseWatchlistEntity w1 where w1.userId = :userId
         )
           and w2.userId <> :userId
         group by w2.userId
         order by count(distinct w2.errorCaseId) desc
    """)
    fun findCoOccurringUserIds(
        @Param("userId") userId: Long,
        pageable: org.springframework.data.domain.Pageable,
    ): List<org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.UserCountProjection>
}
