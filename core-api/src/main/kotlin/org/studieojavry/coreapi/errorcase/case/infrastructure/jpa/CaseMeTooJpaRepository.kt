package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseMeTooEntity

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
}
