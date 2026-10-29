package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink.LinkSource
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubUrl
import java.time.Instant

/**
 * Case 에 GitHub Issue/PR 주소를 직접 붙인다.
 *
 * 순서: 주소 해석 → Case 쓰기 권한(워크스페이스 Case 는 현재 멤버) → 레포가 활성 연결에 있나 → **범위 일치** → GitHub 에서 최신 정보 조회
 * → (트랜잭션) 스냅샷·장부·연결·타임라인 기록. GitHub 호출은 트랜잭션 밖(DB 커넥션을 붙잡지 않게).
 *
 * 이미 붙어 있으면 그대로 돌려주고(created=false), "관련 없음"으로 뗐던 것이면 다시 붙인다.
 * Issue 를 붙이면 이어서 그 Issue 를 닫는 PR 을 **소급 부착**한다([IssuePullRequestBackfiller]).
 */
@Service
@ConditionalOnGithubEnabled
class LinkGithubToCaseUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val policy: GithubCaseAccessPolicy,
    private val scopeGuard: GithubScopeGuard,
    private val githubApi: GithubAppApiPort,
    private val recorder: GithubEvidenceRecorder,
    private val backfiller: IssuePullRequestBackfiller,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    data class Result(val link: CaseGithubLink, val created: Boolean, val backfilledPullRequests: Int = 0)

    fun invoke(caseId: Long, requesterUserId: Long, rawUrl: String): Result {
        val url = GithubUrl.parse(rawUrl)
            ?: throw GithubUrlInvalidException("지원하지 않는 GitHub 주소 (github.com 의 issues/pull 주소만 가능)")
        val errorCase = errorCases.findById(caseId) ?: throw ErrorCaseNotFoundException(caseId)
        policy.requireWrite(errorCase, requesterUserId)

        val (repository, installation) = scopeGuard.resolve(url.fullName)
        scopeGuard.requireSameScope(installation, errorCase)

        // 이름은 레포 장부의 현재 이름으로 조회(대소문자·rename 차이 흡수).
        val owner = repository.fullName.substringBefore('/')
        val name = repository.fullName.substringAfter('/')
        val item = githubApi.fetchItem(installation.installationId, owner, name, url.number)
            ?: throw GithubItemNotFoundException("GitHub 에서 찾을 수 없음: ${repository.fullName}#${url.number}")

        return when (item) {
            is GithubAppApiPort.PullRequestItem -> {
                val ci = fetchCi(installation.installationId, owner, name, item.snapshot)
                tx.execute { linkPullRequest(caseId, requesterUserId, item, ci) }!!
            }
            is GithubAppApiPort.IssueItem -> {
                val result = tx.execute { linkIssue(caseId, requesterUserId, item) }!!
                val issueRef = GithubItemRef(item.repoId, item.snapshot.number)
                result.copy(backfilledPullRequests = backfiller.backfill(errorCase, issueRef, item.pullRequestCandidates))
            }
        }
    }

    /** CI 는 부가 정보 — 조회 실패해도 연결은 진행. 다음 check_suite 웹훅(P1-d)이 채운다. */
    private fun fetchCi(installationId: Long, owner: String, name: String, pr: GithubPullRequestSnapshot): CiSummary? {
        if (pr.headSha.isBlank()) return null
        return runCatching { CiSummary.of(pr.headSha, githubApi.fetchCommitCheckRuns(installationId, owner, name, pr.headSha)) }
            .onFailure { log.warn { "github ci fetch failed: repo=$owner/$name pr=${pr.number} cause=${it.message}" } }
            .getOrNull()
    }

    private fun linkPullRequest(caseId: Long, userId: Long, item: GithubAppApiPort.PullRequestItem, ci: CiSummary?): Result {
        val now = Instant.now()
        val pr = recorder.savePullRequest(item, ci)
        val ref = GithubItemRef(pr.repoId, pr.number)
        val outcome = recorder.attach(
            caseId, GithubRefKind.PULL_REQUEST, ref, LinkSource.MANUAL, userId,
            stateEvents = recorder.pullRequestStateEvents(caseId, pr, now), now = now,
        )
        log.info { "[github] linked case=$caseId PR ${ref.repoId}#${ref.number} by=$userId created=${outcome.created}" }
        return Result(outcome.link!!, outcome.created)
    }

    private fun linkIssue(caseId: Long, userId: Long, item: GithubAppApiPort.IssueItem): Result {
        val now = Instant.now()
        val issue = recorder.saveIssue(item.snapshot)
        val ref = GithubItemRef(issue.repoId, issue.number)
        val outcome = recorder.attach(
            caseId, GithubRefKind.ISSUE, ref, LinkSource.MANUAL, userId,
            stateEvents = recorder.issueStateEvents(caseId, issue), now = now,
        )
        log.info { "[github] linked case=$caseId ISSUE ${ref.repoId}#${ref.number} by=$userId created=${outcome.created}" }
        return Result(outcome.link!!, outcome.created)
    }
}
