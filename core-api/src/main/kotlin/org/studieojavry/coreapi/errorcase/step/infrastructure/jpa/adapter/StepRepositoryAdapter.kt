package org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.adapter

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.StepJpaRepository
import org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.entity.StepEntity
import java.time.LocalDateTime

@Component
class StepRepositoryAdapter(
    private val jpa: StepJpaRepository
) : StepRepositoryPort {

    override fun save(step: Step): Step = jpa.save(StepEntity.fromDomain(step)).toDomain()

    override fun findById(id: Long): Step? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAllByErrorCaseId(errorCaseId: Long): List<Step> =
        jpa.findAllByErrorCaseIdOrderByOrderIndexAscIdAsc(errorCaseId).map { it.toDomain() }

    override fun countByErrorCaseId(errorCaseId: Long): Long = jpa.countByErrorCaseId(errorCaseId)

    override fun existsSuccessByErrorCaseId(errorCaseId: Long): Boolean =
        jpa.existsByErrorCaseIdAndStatus(errorCaseId, StepStatus.RESOLVED)

    override fun delete(id: Long) = jpa.deleteById(id)
    override fun deleteAllByErrorCaseId(errorCaseId: Long) { jpa.deleteAllByErrorCaseId(errorCaseId) }

    // ── Recent Activity ───────────────────────────────────────
    override fun findRecentByAuthor(authorUserId: Long, since: LocalDateTime, limit: Int): List<Step> =
        jpa.findRecentByAuthor(authorUserId, since, PageRequest.of(0, limit)).map { it.toDomain() }

    override fun findMaxCreatedAtByCaseIds(caseIds: Collection<Long>): Map<Long, LocalDateTime> {
        if (caseIds.isEmpty()) return emptyMap()
        return jpa.findMaxCreatedAtGrouped(caseIds).associate { it.errorCaseId to it.value }
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
                source = org.studieojavry.coreapi.errorcase.case.application.port.CaseActivitySource.STEP_ADDED,
            )
        }
    }

    override fun countByAuthorSince(authorUserId: Long, since: LocalDateTime): Long =
        jpa.countByAuthorSince(authorUserId, since)
}
