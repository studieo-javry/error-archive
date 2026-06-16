package org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa.adapter

import org.springframework.data.domain.PageRequest
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

    override fun findActivitiesByCaseIdsSince(
        caseIds: Collection<Long>,
        globalSince: java.time.LocalDateTime,
    ): List<org.studieojavry.coreapi.errorcase.case.application.port.CaseActivityRow> {
        if (caseIds.isEmpty()) return emptyList()
        return jpa.findActivitiesByCaseIdsSince(caseIds, globalSince).map {
            org.studieojavry.coreapi.errorcase.case.application.port.CaseActivityRow(
                errorCaseId = it.errorCaseId,
                activityId = it.activityId,
                createdAt = it.createdAt,
                authorUserId = it.authorUserId,
                source = org.studieojavry.coreapi.errorcase.case.application.port.CaseActivitySource.SOLUTION_REGISTERED,
            )
        }
    }

    override fun findRecentByAuthor(authorUserId: Long, since: java.time.LocalDateTime, limit: Int): List<Solution> =
        jpa.findRecentByAuthor(authorUserId, since, PageRequest.of(0, limit)).map { it.toDomain() }

    override fun countByAuthorSince(authorUserId: Long, since: java.time.LocalDateTime): Long =
        jpa.countByAuthorSince(authorUserId, since)

    override fun delete(id: Long) = jpa.deleteById(id)
    override fun deleteAllByErrorCaseId(errorCaseId: Long) { jpa.deleteAllByErrorCaseId(errorCaseId) }
}