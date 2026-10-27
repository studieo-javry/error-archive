package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort.ClosingRefSource
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubUrl
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import java.time.Instant

/**
 * Case 에 GitHub Issue/PR 주소를 직접 붙인다 (P1-a — 수동 연결).
 *
 * 순서: 주소 해석 → Case 쓰기 권한 → 레포가 활성 연결에 있나 → **범위 일치** → GitHub 에서 최신 정보 조회
 * → (트랜잭션) 스냅샷·장부·연결·타임라인 기록. GitHub 호출은 트랜잭션 밖(DB 커넥션을 붙잡지 않게).
 *
 * 이미 붙어 있으면 그대로 돌려주고(created=false), "관련 없음"으로 뗐던 것이면 다시 붙인다.
 * Issue 를 붙여도 그 Issue 를 닫는 PR 소급 부착은 하지 않는다 — P1-b.
 */
@Service
@ConditionalOnGithubEnabled
class LinkGithubToCaseUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val access: ErrorCaseAccess,
    private val scopeGuard: GithubScopeGuard,
    private val githubApi: GithubAppApiPort,
    private val evidence: GithubEvidenceRepositoryPort,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    data class Result(val link: CaseGithubLink, val created: Boolean)

    fun invoke(caseId: Long, requesterUserId: Long, rawUrl: String): Result {
        val url = GithubUrl.parse(rawUrl)
            ?: throw GithubUrlInvalidException("지원하지 않는 GitHub 주소 (github.com 의 issues/pull 주소만 가능)")
        val errorCase = errorCases.findById(caseId) ?: throw ErrorCaseNotFoundException(caseId)
        access.requireWrite(errorCase, requesterUserId)

        val (repository, installation) = scopeGuard.resolve(url.fullName)
        scopeGuard.requireSameScope(installation, errorCase)

        // 이름은 레포 장부의 현재 이름으로 조회(대소문자·rename 차이 흡수).
        val owner = repository.fullName.substringBefore('/')
        val name = repository.fullName.substringAfter('/')
        val item = githubApi.fetchItem(installation.installationId, owner, name, url.number)
            ?: throw GithubItemNotFoundException("GitHub 에서 찾을 수 없음: ${repository.fullName}#${url.number}")
        val ci = (item as? GithubAppApiPort.PullRequestItem)?.let { fetchCi(installation.installationId, owner, name, it.snapshot) }

        return tx.execute { persist(caseId, requesterUserId, item, ci) }!!
    }

    /** CI 는 부가 정보 — 조회 실패해도 연결은 진행. 다음 check_suite 웹훅(P1-d)이 채운다. */
    private fun fetchCi(installationId: Long, owner: String, name: String, pr: GithubPullRequestSnapshot): CiSummary? {
        if (pr.headSha.isBlank()) return null
        return runCatching { CiSummary.of(pr.headSha, githubApi.fetchCommitCheckRuns(installationId, owner, name, pr.headSha)) }
            .onFailure { log.warn { "github ci fetch failed: repo=$owner/$name pr=${pr.number} cause=${it.message}" } }
            .getOrNull()
    }

    private fun persist(caseId: Long, userId: Long, item: GithubAppApiPort.GithubItem, ci: CiSummary?): Result {
        val now = Instant.now()
        val kind: GithubRefKind
        val ref: GithubItemRef
        val stateEvents = mutableListOf<CaseGithubEvent>()

        when (item) {
            is GithubAppApiPort.IssueItem -> {
                val fetched = item.snapshot
                val issue = evidence.findIssue(GithubItemRef(fetched.repoId, fetched.number))
                    ?.also { it.applyIfNewer(fetched) } ?: fetched
                evidence.saveIssue(issue)
                kind = GithubRefKind.ISSUE
                ref = GithubItemRef(issue.repoId, issue.number)
                if (issue.state == "CLOSED") stateEvents += CaseGithubEvent.issueClosed(caseId, issue)
            }
            is GithubAppApiPort.PullRequestItem -> {
                val fetched = item.snapshot
                val pr = evidence.findPullRequest(GithubItemRef(fetched.repoId, fetched.number))
                    ?.also { it.applyIfNewer(fetched) } ?: fetched
                ci?.let { pr.recordCi(it) }
                evidence.savePullRequest(pr)
                kind = GithubRefKind.PULL_REQUEST
                ref = GithubItemRef(pr.repoId, pr.number)
                evidence.replaceClosingRefs(ref, item.closingIssues, ClosingRefSource.GITHUB)

                stateEvents += CaseGithubEvent.prOpened(caseId, pr)
                when {
                    pr.isMerged -> stateEvents += CaseGithubEvent.prMerged(caseId, pr)
                    pr.state == GithubPullRequestSnapshot.STATE_CLOSED -> stateEvents += CaseGithubEvent.prClosed(caseId, pr)
                }
                pr.ci?.let { stateEvents += CaseGithubEvent.ciCompleted(caseId, pr.repoId, pr.number, it, now) }
            }
        }

        val existing = evidence.findLink(caseId, kind, ref)
        if (existing != null && existing.isActive) {
            // 이미 붙어 있음 — 그 사이 생긴 상태 변화(머지 등)만 타임라인에 보충(dedupe 로 중복 없음).
            stateEvents.forEach(evidence::recordEvent)
            return Result(existing, created = false)
        }

        val link = if (existing != null) {
            existing.reattach(userId, now)
            evidence.saveLink(existing)
        } else {
            evidence.saveLink(CaseGithubLink.manual(caseId, kind, ref.repoId, ref.number, userId, now))
        }
        evidence.recordEvent(CaseGithubEvent.linked(caseId, kind, ref.repoId, ref.number, link.linkSource, userId, now))
        stateEvents.forEach(evidence::recordEvent)
        log.info { "[github] linked case=$caseId kind=$kind ref=${ref.repoId}#${ref.number} by=$userId reattach=${existing != null}" }
        return Result(link, created = true)
    }
}
