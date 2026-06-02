package org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa.SolutionJpaRepository
import org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa.entity.SolutionEntity

@Component
class SolutionRepositoryAdapter(
    private val jpa: SolutionJpaRepository
) : SolutionRepositoryPort {

    override fun save(solution: Solution): Solution = jpa.save(SolutionEntity.fromDomain(solution)).toDomain()

    override fun findById(id: Long): Solution? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAllByErrorCaseId(errorCaseId: Long): List<Solution> =
        jpa.findAllByErrorCaseIdOrderByCreatedAtAsc(errorCaseId).map { it.toDomain() }

    override fun findReferencingStep(stepId: Long): List<Solution> =
        jpa.findReferencingStep(stepId).map { it.toDomain() }

    override fun delete(id: Long) = jpa.deleteById(id)
    override fun deleteAllByErrorCaseId(errorCaseId: Long) { jpa.deleteAllByErrorCaseId(errorCaseId) }
}