package org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.StepJpaRepository
import org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.entity.StepEntity

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

    override fun findActivitiesByCaseIdsSince(
        caseIds: Collection<Long>,
        globalSince: java.time.LocalDateTime,
    ): List<org.studieojavry.coreapi.errorcase.case.application.port.CaseActivityRow> {
        if (caseIds.isEmpty()) return emptyList()
        return jpa.findActivitiesByCaseIdsSince(caseIds, globalSince).map {
            org.studieojavry.coreapi.errorcase.case.application.port.CaseActivityRow(
                errorCaseId = it.errorCaseId,
                createdAt = it.createdAt,
                authorUserId = it.authorUserId,
                source = org.studieojavry.coreapi.errorcase.case.application.port.CaseActivitySource.STEP_ADDED,
            )
        }
    }

    override fun delete(id: Long) = jpa.deleteById(id)
    override fun deleteAllByErrorCaseId(errorCaseId: Long) { jpa.deleteAllByErrorCaseId(errorCaseId) }
}
