package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseViewEntity
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseViewId

interface CaseViewJpaRepository : JpaRepository<CaseViewEntity, CaseViewId> {
    fun findAllByUserIdAndErrorCaseIdIn(userId: Long, errorCaseIds: Collection<Long>): List<CaseViewEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from CaseViewEntity v where v.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int
}
