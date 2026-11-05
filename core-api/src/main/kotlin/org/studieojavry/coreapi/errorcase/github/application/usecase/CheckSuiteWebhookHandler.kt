package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * `check_suite` 웹훅 (P1-d) — 커밋 CI 가 끝나면 그 커밋을 쓰는 PR 의 CI 요약과 Case 타임라인을 갱신한다.
 *
 * 1. `completed` 만. 저장소·설치가 활성이 아니거나 이 레포 PR 이 어느 Case 에도 안 붙어 있으면 건너뜀(GitHub 를 부르지 않음)
 * 2. PR 찾기 = payload `pull_requests` ∪ 장부에서 이 커밋이 head/머지 커밋인 PR. 둘 다 비면 GitHub 에 커밋→PR 을 묻는다 —
 *    push(머지) 로 돈 CI 는 `pull_requests` 가 비어 오고, 머지 커밋 sha 를 아직 모르는 PR(옛 행·웹훅 순서 뒤바뀜)도 있어서
 * 3. 붙어 있는 PR 만. 장부의 head/머지 커밋이 이 커밋이 아니면 PR 을 다시 조회해 최신화(그래도 아니면 지난 커밋 → 건너뜀)
 * 4. CI 앱이 여러 개라 suite 는 앱마다 따로 오지만, 이 커밋의 check run **전부**를 다시 모아 한 줄로 요약
 * 5. 카드 요약: head 커밋 것은 항상, 머지 커밋 것은 head 에 검사가 없을 때만. 타임라인: 최종 결과(통과/실패)만, PR·커밋당 한 줄
 *
 * inbox 처리기의 건별 트랜잭션 안에서 실행된다. GitHub 조회 실패는 예외로 올려 inbox 재시도에 맡긴다.
 */
@Component
@ConditionalOnGithubEnabled
class CheckSuiteWebhookHandler(
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
        if (action != "completed") return false
        val suite = root.path("check_suite")
        val sha = suite.path("head_sha").takeIf { it.isString }?.asString() ?: return false
        val repo = installations.findRepository(root.path("repository").path("id").asLong())
            ?.takeIf { it.isActive } ?: return false
        if (installationId != null && installationId != repo.installationId) return false
        val installation = installations.findById(repo.installationId)
            ?.takeIf { it.status == GithubInstallationStatus.ACTIVE } ?: return false
        if (!evidence.hasActivePullRequestLinkInRepo(repo.repoId)) return false

        val owner = repo.fullName.substringBefore('/')
        val name = repo.fullName.substringAfter('/')
        val fromPayload = suite.path("pull_requests").let { prs ->
            (0 until (if (prs.isArray) prs.size() else 0)).map { prs.get(it) }
                .filter { it.path("base").path("repo").path("id").asLong() == repo.repoId }
                .map { it.path("number").asInt() }
        }
        val known = (fromPayload + evidence.findPullRequestsByCommit(repo.repoId, sha).map { it.number }).distinct()
        val numbers = known.ifEmpty { githubApi.listPullRequestNumbersForCommit(installation.installationId, owner, name, sha) }

        val linked = numbers.distinct()
            .map { GithubItemRef(repo.repoId, it) }
            .filter { permittedCaseIds(evidence.findActiveLinkedCaseIds(GithubRefKind.PULL_REQUEST, it), repo.repoId).isNotEmpty() }
        if (linked.isEmpty()) return false

        val summary by lazy { CiSummary.of(sha, githubApi.fetchCommitCheckRuns(installation.installationId, owner, name, sha)) }
        val now = Instant.now()
        var recorded = 0
        for (ref in linked) {
            val pr = currentSnapshot(installation.installationId, owner, name, ref, sha) ?: continue
            val ci = summary ?: break   // 검사가 하나도 없는 커밋(다른 앱의 빈 suite) — 남길 것이 없음
            if (pr.recordCi(ci)) evidence.savePullRequest(pr)
            val caseIds = permittedCaseIds(evidence.findActiveLinkedCaseIds(GithubRefKind.PULL_REQUEST, ref), repo.repoId)
            caseIds.forEach { caseId -> recorder.ciEvent(caseId, pr, ci, now)?.let(evidence::recordEvent) }
            recorded++
            log.info { "[github] check_suite pr=${repo.fullName}#${ref.number} sha=${sha.take(7)} ci=${ci.conclusion} merge=${sha != pr.headSha} cases=${caseIds.size}" }
        }
        return recorded > 0
    }

    /** 이 커밋이 PR 의 head/머지 커밋인 스냅샷. 장부가 모르면 GitHub 에서 다시 받아 최신화하고, 그래도 아니면 null(지난 커밋). */
    private fun currentSnapshot(installationId: Long, owner: String, name: String, ref: GithubItemRef, sha: String): GithubPullRequestSnapshot? {
        evidence.findPullRequest(ref)?.takeIf { it.ownsCommit(sha) }?.let { return it }
        val item = githubApi.fetchItem(installationId, owner, name, ref.number) as? GithubAppApiPort.PullRequestItem ?: return null
        return recorder.savePullRequest(item, ci = null).takeIf { it.ownsCommit(sha) }
    }
}
