package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.usecase.StepNotFoundException
import java.time.Instant

/**
 * Step 의 GitHub 근거 꼬리표를 [linkIds] 로 맞춘다(빈 목록 = 모두 해제). Case 쓰기 권한 필요.
 *
 * 고를 수 있는 건 **이미 이 Case 에 붙어 있는(떼지 않은) 근거뿐** — 그래서 범위 검사(설치 범위 = Case 범위)는
 * Case 연결 때 끝났고 여기서 다시 하지 않는다. 내용은 복사하지 않으므로 비공개 가림도 근거 조회 규칙 그대로.
 */
@Service
@ConditionalOnGithubEnabled
class SetStepGithubLinksUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val access: ErrorCaseAccess,
    private val steps: StepRepositoryPort,
    private val evidence: GithubEvidenceRepositoryPort,
) {
    @Transactional
    fun invoke(caseId: Long, stepId: Long, requesterUserId: Long, linkIds: List<Long>): List<Long> {
        val errorCase = errorCases.findById(caseId) ?: throw ErrorCaseNotFoundException(caseId)
        access.requireWrite(errorCase, requesterUserId)
        steps.findById(stepId)?.takeIf { it.errorCaseId == caseId } ?: throw StepNotFoundException(stepId)

        val wanted = linkIds.distinct()
        val active = evidence.findLinks(caseId).filter { it.isActive }.mapNotNull { it.id }.toSet()
        val invalid = wanted.filter { it !in active }
        if (invalid.isNotEmpty()) {
            throw GithubStepLinkInvalidException("이 Case 에 붙어 있는 근거가 아님: case=$caseId linkIds=$invalid")
        }
        evidence.replaceStepLinks(caseId, stepId, wanted, requesterUserId, Instant.now())
        return wanted
    }
}
