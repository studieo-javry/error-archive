package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort.ClosingRefSource
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink.LinkSource
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import java.time.Instant

/**
 * 소급 부착 — Issue 를 Case 에 붙인 순간, **이미 그 Issue 를 닫겠다고 나선 PR** 을 찾아 같이 붙인다.
 *
 * 후보 = 장부(설치 이후 웹훅/연결로 본 PR) ∪ Issue 타임라인(설치 전에 열린 PR, 사이드바 수동 연결 PR).
 * 후보마다 PR 을 다시 조회해 closingIssuesReferences 에 이 Issue 가 **실제로 있을 때만** 붙인다(단순 언급은 제외).
 *
 * 붙이는 규칙은 자동 부착과 같다: 레포가 활성 연결에 있고 **설치 범위 = Case 범위**, "관련 없음"으로 뗐던 PR 은 다시 안 붙임.
 * 후보 하나가 실패해도 나머지는 계속 — 소급은 덤이라 Issue 연결 자체를 실패시키지 않는다.
 */
@Component
@ConditionalOnGithubEnabled
class IssuePullRequestBackfiller(
    private val githubApi: GithubAppApiPort,
    private val installations: GithubInstallationRepositoryPort,
    private val evidence: GithubEvidenceRepositoryPort,
    private val recorder: GithubEvidenceRecorder,
    private val scopeGuard: GithubScopeGuard,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    /** @return 새로 붙은 PR 수 */
    fun backfill(errorCase: ErrorCase, issue: GithubItemRef, timelineCandidates: List<GithubAppApiPort.PullRequestCandidate>): Int {
        val caseId = requireNotNull(errorCase.id)
        val candidates = candidates(issue, timelineCandidates)
        var attached = 0
        for (candidate in candidates) {
            runCatching { if (tryAttach(errorCase, caseId, issue, candidate)) attached++ }
                .onFailure { log.warn { "[github] backfill candidate failed: case=$caseId issue=$issue pr=${candidate.fullName}#${candidate.number} cause=${it.message}" } }
        }
        if (candidates.isNotEmpty()) {
            log.info { "[github] backfill case=$caseId issue=${issue.repoId}#${issue.number} candidates=${candidates.size} attached=$attached" }
        }
        return attached
    }

    private fun candidates(
        issue: GithubItemRef,
        timeline: List<GithubAppApiPort.PullRequestCandidate>,
    ): List<GithubAppApiPort.PullRequestCandidate> {
        val fromLedger = evidence.findPullRequestsClosing(issue)
        val names = installations.findRepositoriesByIds(fromLedger.map { it.repoId }.toSet()).associate { it.repoId to it.fullName }
        val ledgerCandidates = fromLedger.mapNotNull { ref ->
            names[ref.repoId]?.let { GithubAppApiPort.PullRequestCandidate(ref.repoId, it, ref.number) }
        }
        return (ledgerCandidates + timeline)
            .distinctBy { it.repoId to it.number }
            .take(MAX_CANDIDATES)
    }

    private fun tryAttach(
        errorCase: ErrorCase,
        caseId: Long,
        issue: GithubItemRef,
        candidate: GithubAppApiPort.PullRequestCandidate,
    ): Boolean {
        val prRef = GithubItemRef(candidate.repoId, candidate.number)
        // 이미 붙었거나 "관련 없음"으로 뗀 PR 은 GitHub 에 묻지도 않는다.
        if (evidence.findLink(caseId, GithubRefKind.PULL_REQUEST, prRef) != null) return false

        val repo = installations.findRepository(candidate.repoId)?.takeIf { it.isActive } ?: return false
        val installation = installations.findById(repo.installationId)
            ?.takeIf { it.status == GithubInstallationStatus.ACTIVE } ?: return false
        if (!scopeGuard.isSameScope(installation, errorCase)) return false

        val owner = repo.fullName.substringBefore('/')
        val name = repo.fullName.substringAfter('/')
        val item = githubApi.fetchItem(installation.installationId, owner, name, candidate.number)
            as? GithubAppApiPort.PullRequestItem ?: return false

        if (issue !in item.closingIssues) {
            // 언급만 한 PR — 붙이지 않되, 장부에 예전 정보가 남아 있었다면 지금 상태로 바로잡는다.
            tx.executeWithoutResult { evidence.replaceClosingRefs(prRef, item.closingIssues, ClosingRefSource.GITHUB) }
            return false
        }

        val ci = fetchCi(installation.installationId, owner, name, item.snapshot)
        return tx.execute {
            val now = Instant.now()
            val pr = recorder.savePullRequest(item, ci)
            recorder.attach(
                caseId, GithubRefKind.PULL_REQUEST, prRef, LinkSource.VIA_ISSUE, userId = null,
                stateEvents = recorder.pullRequestStateEvents(caseId, pr, now), viaIssue = issue, now = now,
            ).created
        } == true
    }

    private fun fetchCi(installationId: Long, owner: String, name: String, pr: GithubPullRequestSnapshot): CiSummary? {
        if (pr.headSha.isBlank()) return null
        return runCatching { CiSummary.of(pr.headSha, githubApi.fetchCommitCheckRuns(installationId, owner, name, pr.headSha)) }
            .getOrNull()
    }

    companion object {
        /** 소급 후보 상한 — Issue 하나에 PR 이 수십 개 붙는 일은 드물고, 호출 비용(후보당 GraphQL+REST)을 제한. */
        const val MAX_CANDIDATES = 20
    }
}
