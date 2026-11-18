package org.studieojavry.coreapi.errorcase.eventevidence.application.port

import org.studieojavry.coreapi.errorcase.eventevidence.domain.model.CaseEventEvidence

/**
 * 수집 근거 Case 스냅샷 저장소(PR-2). error_case_attachment 와 같은 "FK 없음 + 애플리케이션 cascade" 정책.
 */
interface CaseEventEvidenceRepositoryPort {
    fun save(evidence: CaseEventEvidence): CaseEventEvidence
    /** Case 상세(소유자 전용) 조회 — 최신 반영 우선(id DESC). */
    fun findAllByErrorCaseId(errorCaseId: Long): List<CaseEventEvidence>
    /** Case 삭제 cascade 정리(bulk). 반환=삭제 행 수. */
    fun deleteAllByErrorCaseId(errorCaseId: Long): Int
}
