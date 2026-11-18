package org.studieojavry.coreapi.errorcase.eventevidence.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.eventevidence.application.port.CaseEventEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.eventevidence.domain.model.CaseEventEvidence
import org.studieojavry.coreapi.errorcase.eventevidence.infrastructure.jpa.CaseEventEvidenceJpaRepository
import org.studieojavry.coreapi.errorcase.eventevidence.infrastructure.jpa.entity.CaseEventEvidenceEntity

@Component
class CaseEventEvidenceRepositoryAdapter(
    private val jpa: CaseEventEvidenceJpaRepository,
) : CaseEventEvidenceRepositoryPort {

    override fun save(evidence: CaseEventEvidence): CaseEventEvidence =
        jpa.save(
            CaseEventEvidenceEntity(
                id = evidence.id,
                errorCaseId = evidence.errorCaseId,
                sourceCaptureEventId = evidence.sourceCaptureEventId,
                sourceClientEventId = evidence.sourceClientEventId,
                eventType = evidence.eventType,
                observedAt = evidence.observedAt,
                receivedAt = evidence.receivedAt,
                redactedPayload = evidence.redactedPayload,
                truncated = evidence.truncated,
                reviewedByUserId = evidence.reviewedByUserId,
                reviewedAt = evidence.reviewedAt,
                visibilityPolicy = evidence.visibilityPolicy,
            ),
        ).toDomain()

    override fun findAllByErrorCaseId(errorCaseId: Long): List<CaseEventEvidence> =
        jpa.findAllByErrorCaseIdOrderByIdDesc(errorCaseId).map { it.toDomain() }

    @Transactional
    override fun deleteAllByErrorCaseId(errorCaseId: Long): Int =
        jpa.deleteAllByErrorCaseId(errorCaseId)

    private fun CaseEventEvidenceEntity.toDomain(): CaseEventEvidence = CaseEventEvidence(
        id = id,
        errorCaseId = errorCaseId,
        sourceCaptureEventId = sourceCaptureEventId,
        sourceClientEventId = sourceClientEventId,
        eventType = eventType,
        observedAt = observedAt,
        receivedAt = receivedAt,
        redactedPayload = redactedPayload,
        truncated = truncated,
        reviewedByUserId = reviewedByUserId,
        reviewedAt = reviewedAt,
        visibilityPolicy = visibilityPolicy,
    )
}
