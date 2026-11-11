package org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa.adapter

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.solution.domain.model.SolutionVerification
import org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa.SolutionJpaRepository
import org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa.entity.SolutionEntity
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

@Component
class SolutionRepositoryAdapter(
    private val jpa: SolutionJpaRepository,
    private val objectMapper: ObjectMapper,
) : SolutionRepositoryPort {

    override fun save(solution: Solution): Solution {
        val entity = SolutionEntity.fromDomain(solution)
        entity.verification = solution.verification?.let { objectMapper.writeValueAsString(it) }
        return jpa.save(entity).toModel()
    }

    override fun findById(id: Long): Solution? = jpa.findById(id).orElse(null)?.toModel()

    override fun findAllByErrorCaseId(errorCaseId: Long): List<Solution> =
        jpa.findAllByErrorCaseIdOrderByCreatedAtAsc(errorCaseId).map { it.toModel() }

    override fun findReferencingStep(stepId: Long): List<Solution> =
        jpa.findReferencingStep(stepId).map { it.toModel() }

    override fun delete(id: Long) = jpa.deleteById(id)
    override fun deleteAllByErrorCaseId(errorCaseId: Long) { jpa.deleteAllByErrorCaseId(errorCaseId) }

    // ── Recent Activity ───────────────────────────────────────
    override fun findRecentByAuthor(authorUserId: Long, since: LocalDateTime, limit: Int): List<Solution> =
        jpa.findRecentByAuthor(authorUserId, since, PageRequest.of(0, limit)).map { it.toModel() }

    /** 엔티티 → 도메인. verification(jsonb String)을 SolutionVerification 으로 역직렬화한다. */
    private fun SolutionEntity.toModel(): Solution = Solution.rehydrate(
        id = id!!,
        errorCaseId = errorCaseId,
        authorUserId = authorUserId,
        title = title,
        stepIds = stepIds,
        description = description,
        verification = verification?.let { objectMapper.readValue(it, SolutionVerification::class.java) },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    override fun findMaxCreatedAtByCaseIds(caseIds: Collection<Long>): Map<Long, LocalDateTime> {
        if (caseIds.isEmpty()) return emptyMap()
        return jpa.findMaxCreatedAtGrouped(caseIds).associate { it.errorCaseId to it.value }
    }

    override fun countUnreadByCaseIds(caseIds: Collection<Long>, userId: Long): Map<Long, Long> {
        if (caseIds.isEmpty()) return emptyMap()
        return jpa.countUnreadByCaseIds(caseIds, userId).associate { it.errorCaseId to it.count }
    }

    override fun countByCaseIds(caseIds: Collection<Long>): Map<Long, Long> {
        if (caseIds.isEmpty()) return emptyMap()
        return jpa.countGrouped(caseIds).associate { it.errorCaseId to it.count }
    }

    override fun countSinceExcludingAuthor(errorCaseId: Long, since: LocalDateTime, excludeUserId: Long): Long =
        jpa.countSinceExcludingAuthor(errorCaseId, since, excludeUserId)

    override fun findActivitiesByCaseIdsSince(
        caseIds: Collection<Long>,
        globalSince: LocalDateTime,
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

    override fun countByAuthorSince(authorUserId: Long, since: LocalDateTime): Long =
        jpa.countByAuthorSince(authorUserId, since)
}