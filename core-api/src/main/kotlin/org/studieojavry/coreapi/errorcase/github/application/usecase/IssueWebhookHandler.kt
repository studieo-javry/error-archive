package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * `issues` 웹훅 (P1-d) — Case 에 붙은 Issue 의 스냅샷을 최신으로 맞추고 닫힘·다시 열림을 타임라인에 남긴다.
 *
 *  - closed / reopened / edited : GitHub 에서 다시 조회("웹훅은 알림, 진실은 API") → 스냅샷 반영(더 새로울 때만)
 *    → 지금 상태가 닫힘이면 ISSUE_CLOSED, 다시 열렸으면 ISSUE_REOPENED. edited 는 제목 등 스냅샷만
 *  - deleted / transferred      : 그 자리에 Issue 가 더 이상 없음 → 스냅샷을 삭제 표시(근거·타임라인은 남김). 조회하지 않음
 *
 * 어느 Case 에도 붙어 있지 않은 Issue 는 GitHub 를 부르지 않고 건너뜀. 연결은 바꾸지 않는다(PR 자동 부착은 PR 웹훅 몫).
 * inbox 처리기의 건별 트랜잭션 안에서 실행된다. GitHub 조회 실패는 예외로 올려 inbox 재시도에 맡긴다.
 */
@Component
@ConditionalOnGithubEnabled
class IssueWebhookHandler(
    private val installations: GithubInstallationRepositoryPort,
    private val githubApi: GithubAppApiPort,
    private val evidence: GithubEvidenceRepositoryPort,
    private val recorder: GithubEvidenceRecorder,
    private val errorCases: ErrorCaseRepositoryPort,
    private val guard: GithubConnectionGuard,
) {
    private val log = KotlinLogging.logger {}

    /** 공간별 게이트: 링크된 Case 중 "그 Case 공간이 이 repo 를 선택(연결)" 한 것만 남긴다. */
    private fun permittedCaseIds(caseIds: List<Long>, repoId: Long): List<Long> =
        caseIds.filter { id -> errorCases.findById(id)?.let { guard.selects(it, repoId) } == true }

    /** @return 처리했으면 true, 관심 없는 이벤트면 false(SKIPPED) */
    fun handle(action: String?, installationId: Long?, root: JsonNode): Boolean {
        if (action !in HANDLED_ACTIONS) return false
        val repo = installations.findRepository(root.path("repository").path("id").asLong())
            ?.takeIf { it.isActive } ?: return false
        if (installationId != null && installationId != repo.installationId) return false
        val installation = installations.findById(repo.installationId)
            ?.takeIf { it.status == GithubInstallationStatus.ACTIVE } ?: return false

        val issueNode = root.path("issue")
        val ref = GithubItemRef(repo.repoId, issueNode.path("number").asInt())
        val caseIds = permittedCaseIds(evidence.findActiveLinkedCaseIds(GithubRefKind.ISSUE, ref), repo.repoId)
        if (caseIds.isEmpty()) return false

        if (action == "deleted" || action == "transferred") {
            val issue = evidence.findIssue(ref) ?: return false
            issue.deleted = true
            evidence.saveIssue(issue)
            log.info { "[github] issues $action issue=${repo.fullName}#${ref.number} → 삭제 표시 cases=${caseIds.size}" }
            return true
        }

        val owner = repo.fullName.substringBefore('/')
        val name = repo.fullName.substringAfter('/')
        val item = githubApi.fetchItem(installation.installationId, owner, name, ref.number)
            as? GithubAppApiPort.IssueItem ?: return false
        val issue = recorder.saveIssue(item.snapshot)
        val at = issueNode.path("updated_at").takeIf { it.isString }?.asString()?.let(Instant::parse) ?: issue.sourceUpdatedAt

        val event: (Long) -> CaseGithubEvent? = when {
            action == "closed" && issue.state == STATE_CLOSED -> { caseId -> CaseGithubEvent.issueClosed(caseId, issue) }
            action == "reopened" && issue.state == STATE_OPEN -> { caseId -> CaseGithubEvent.issueReopened(caseId, issue, at) }
            else -> { _ -> null }   // edited, 또는 조회 시점에 이미 상태가 다시 바뀜(다음 웹훅이 기록)
        }
        caseIds.forEach { caseId -> event(caseId)?.let(evidence::recordEvent) }
        log.info { "[github] issues $action issue=${repo.fullName}#${ref.number} state=${issue.state} cases=${caseIds.size}" }
        return true
    }

    /**
     * 놓친 웹훅 보충([GithubEvidenceReconciler]) — 붙어 있는 Issue 를 다시 조회해 스냅샷을 맞추고, 열림↔닫힘이 바뀌었으면 기록한다
     * (다시 열림 시각 = GitHub 갱신 시각). 호출자가 트랜잭션을 연다. GitHub 조회 실패는 예외로 올린다.
     * @return 다시 조회한 Issue — 붙은 Case 가 없거나 GitHub 에서 볼 수 없으면 null
     */
    fun reconcile(installation: GithubInstallation, repo: GithubRepository, number: Int): GithubAppApiPort.IssueItem? {
        val ref = GithubItemRef(repo.repoId, number)
        val caseIds = permittedCaseIds(evidence.findActiveLinkedCaseIds(GithubRefKind.ISSUE, ref), repo.repoId)
        if (caseIds.isEmpty()) return null
        val before = evidence.findIssue(ref)
        val item = githubApi.fetchItem(installation.installationId, repo.fullName.substringBefore('/'), repo.fullName.substringAfter('/'), number)
            as? GithubAppApiPort.IssueItem ?: return null
        val issue = recorder.saveIssue(item.snapshot)
        val event: (Long) -> CaseGithubEvent? = when {
            before?.state == STATE_OPEN && issue.state == STATE_CLOSED -> { caseId -> CaseGithubEvent.issueClosed(caseId, issue) }
            before?.state == STATE_CLOSED && issue.state == STATE_OPEN -> { caseId -> CaseGithubEvent.issueReopened(caseId, issue, issue.sourceUpdatedAt) }
            else -> { _ -> null }
        }
        caseIds.forEach { caseId -> event(caseId)?.let(evidence::recordEvent) }
        log.info { "[github] issues reconcile issue=${repo.fullName}#$number state=${before?.state}→${issue.state} cases=${caseIds.size}" }
        return item
    }

    companion object {
        val HANDLED_ACTIONS = setOf("closed", "reopened", "edited", "deleted", "transferred")
        private const val STATE_OPEN = "OPEN"
        private const val STATE_CLOSED = "CLOSED"
    }
}
