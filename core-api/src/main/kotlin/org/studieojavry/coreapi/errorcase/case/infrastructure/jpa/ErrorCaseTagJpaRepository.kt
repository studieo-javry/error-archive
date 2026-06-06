package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.ErrorCaseTagEntity

interface ErrorCaseTagJpaRepository : JpaRepository<ErrorCaseTagEntity, Long> {

    fun findAllByErrorCaseIdOrderByCreatedAtAscIdAsc(errorCaseId: Long): List<ErrorCaseTagEntity>

    fun findByErrorCaseIdAndTag(errorCaseId: Long, tag: String): ErrorCaseTagEntity?

    fun countByErrorCaseId(errorCaseId: Long): Long

    @Modifying(clearAutomatically = true)
    @Query("delete from ErrorCaseTagEntity t where t.errorCaseId = :caseId and t.tag = :tag")
    fun deleteByErrorCaseIdAndTag(@Param("caseId") errorCaseId: Long, @Param("tag") tag: String): Int

    @Modifying(clearAutomatically = true)
    @Query("delete from ErrorCaseTagEntity t where t.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int
}
