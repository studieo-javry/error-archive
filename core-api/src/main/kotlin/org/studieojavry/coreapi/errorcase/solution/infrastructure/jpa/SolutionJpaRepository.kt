package org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa.entity.SolutionEntity

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
}