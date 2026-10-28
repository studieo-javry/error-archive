package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.usecase.StepNotFoundException
import java.time.Instant

/**
 * Step 의 GitHub 근거 꼬리표를 [linkIds] 로 맞춘다(빈 목록 = 모두 해제). Case 쓰기 권한 필요.
 *
 * 고를 수 있는 건 **이미 이 Case 에 붙어 있는(떼지 않은) 근거뿐** — 그래서 범위 검사(설치 범위 = Case 범위)는
 * Case 연결 때 끝났고 여기서 다시 하지 않는다. 내용은 복사하지 않으므로 비공개 가림도 근거 조회 규칙 그대로.
 *
 * **요청자에게 가려진 근거의 꼬리표는 건드리지 않는다.** 가려진 항목은 응답에 연결 id 가 없어 FE 가 목록에 넣을 수 없으므로,
 * 통째로 맞추기를 그대로 적용하면 본인이 볼 수 없는 꼬리표가 몰래 지워진다. 요청에 가려진 id 가 와도 무시하고,
 * 응답에도 보이는 근거의 id 만 돌려준다.
 */
@Service
@ConditionalOnGithubEnabled
class SetStepGithubLinksUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val policy: GithubCaseAccessPolicy,
    private val steps: StepRepositoryPort,
    private val evidence: GithubEvidenceRepositoryPort,
    private val installations: GithubInstallationRepositoryPort,
) {
    @Transactional
    fun invoke(caseId: Long, stepId: Long, requesterUserId: Long, linkIds: List<Long>): List<Long> {
        val errorCase = errorCases.findById(caseId) ?: throw ErrorCaseNotFoundException(caseId)
        policy.requireWrite(errorCase, requesterUserId)
        steps.findById(stepId)?.takeIf { it.errorCaseId == caseId } ?: throw StepNotFoundException(stepId)

        val activeLinks = evidence.findLinks(caseId).filter { it.isActive }
        val wanted = linkIds.distinct()
        val invalid = wanted.filter { id -> activeLinks.none { it.id == id } }
        if (invalid.isNotEmpty()) {
            throw GithubStepLinkInvalidException("이 Case 에 붙어 있는 근거가 아님: case=$caseId linkIds=$invalid")
        }

        val canSeePrivate = policy.canSeePrivate(errorCase, requesterUserId)
        val repos = installations.findRepositoriesByIds(activeLinks.map { it.repoId }.toSet()).associateBy { it.repoId }
        val hidden = activeLinks.filter { !policy.isVisible(repos[it.repoId], canSeePrivate) }.mapNotNull { it.id }.toSet()
        val current = evidence.findStepLinks(caseId).filter { it.stepId == stepId }.map { it.linkId }
        val next = nextLinks(wanted, hidden, current)

        evidence.replaceStepLinks(caseId, stepId, next.stored, requesterUserId, Instant.now())
        return next.visible
    }

    data class NextLinks(val stored: List<Long>, val visible: List<Long>)

    companion object {
        /** 보이는 근거는 요청대로, 가려진 근거는 지금 상태 그대로. [NextLinks.visible] 만 응답에 쓴다. */
        fun nextLinks(wanted: List<Long>, hidden: Set<Long>, current: List<Long>): NextLinks {
            val visible = wanted.filter { it !in hidden }
            return NextLinks(stored = visible + current.filter { it in hidden }, visible = visible)
        }
    }
}
