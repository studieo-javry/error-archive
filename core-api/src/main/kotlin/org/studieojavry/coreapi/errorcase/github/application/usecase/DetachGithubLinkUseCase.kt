package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import java.time.Instant

/**
 * "관련 없음" — 연결을 떼되 행은 남긴다(자동 재부착 방지). 이미 뗀 연결이면 아무것도 안 함(멱등).
 * Issue 를 떼도 그 Issue 경유로 붙은 PR 은 그대로 둔다(이미 쌓인 근거 — 설계 Q3).
 * 떼면 그 근거의 Step 꼬리표는 지운다 — 다시 연결해도 꼬리표는 복구하지 않는다(P1-e D7).
 */
@Service
@ConditionalOnGithubEnabled
class DetachGithubLinkUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val access: ErrorCaseAccess,
    private val evidence: GithubEvidenceRepositoryPort,
) {
    @Transactional
    fun invoke(caseId: Long, linkId: Long, requesterUserId: Long) {
        val errorCase = errorCases.findById(caseId) ?: throw ErrorCaseNotFoundException(caseId)
        access.requireWrite(errorCase, requesterUserId)
        val link = evidence.findLinkById(linkId)?.takeIf { it.caseId == caseId }
            ?: throw CaseGithubLinkNotFoundException("연결을 찾을 수 없음: case=$caseId link=$linkId")
        if (!link.isActive) return

        val now = Instant.now()
        link.detach(requesterUserId, now)
        evidence.saveLink(link)
        evidence.deleteStepLinksByLinkId(link.id!!)
        evidence.recordEvent(CaseGithubEvent.detached(caseId, link.kind, link.repoId, link.number, requesterUserId, now))
    }
}
