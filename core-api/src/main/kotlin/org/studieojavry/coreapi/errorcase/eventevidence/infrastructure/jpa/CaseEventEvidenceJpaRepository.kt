package org.studieojavry.coreapi.errorcase.eventevidence.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.eventevidence.infrastructure.jpa.entity.CaseEventEvidenceEntity

interface CaseEventEvidenceJpaRepository : JpaRepository<CaseEventEvidenceEntity, Long> {
    fun findAllByErrorCaseIdOrderByIdDesc(errorCaseId: Long): List<CaseEventEvidenceEntity>

    /** Case 삭제 cascade 용 bulk delete(반환=삭제 행 수). clearAutomatically 미사용 — 호출측 cascade 의 다른 pending remove 보존. */
    @Modifying
    @Query("DELETE FROM CaseEventEvidenceEntity e WHERE e.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int
}
