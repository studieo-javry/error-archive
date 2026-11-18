package org.studieojavry.coreapi.errorcase.eventevidence.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.eventevidence.application.port.CaseEventEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.eventevidence.domain.model.CaseEventEvidence

/**
 * Case 의 수집 근거(CaseEventEvidence) 조회 — **소유자 전용**(발행/공유 노출 금지라 requireRead 보다 엄격).
 * 없음 → 404(ErrorCaseNotFoundException), 타인 → 403(CaseEventEvidenceForbiddenException).
 */
@Service
class ListCaseEventEvidenceUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val evidence: CaseEventEvidenceRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(errorCaseId: Long, requesterUserId: Long): List<CaseEventEvidence> {
        val ownerUserId = errorCases.findOwnerUserIdById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        if (ownerUserId != requesterUserId) {
            throw CaseEventEvidenceForbiddenException("case owner != requester (caseId=$errorCaseId)")
        }
        return evidence.findAllByErrorCaseId(errorCaseId)
    }
}

/** 수집 근거는 소유자 전용 — 소유자가 아니면 403. */
class CaseEventEvidenceForbiddenException(message: String) : RuntimeException(message)
