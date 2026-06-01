package org.studieojavry.coreapi.errorcase.step.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.entity.StepEntity

interface StepJpaRepository : JpaRepository<StepEntity, Long> {
    fun findAllByErrorCaseIdOrderByOrderIndexAscIdAsc(errorCaseId: Long): List<StepEntity>
    fun countByErrorCaseId(errorCaseId: Long): Long
    fun existsByErrorCaseIdAndStatus(errorCaseId: Long, status: StepStatus): Boolean

    @Modifying(clearAutomatically = true)
    @Query("delete from StepEntity s where s.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int
}
