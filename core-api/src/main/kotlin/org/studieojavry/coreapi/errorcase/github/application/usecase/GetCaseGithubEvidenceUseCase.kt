package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import java.time.Instant

/**
 * Case 의 GitHub 근거 화면 한 번에 — **Issue ▸ 그 Issue 를 닫는 PR** 관계형 근거 + GitHub 활동 첫 쪽(최신 20건 + 커서).
 * 연결·떼기 뒤에도 이것 하나만 다시 부르면 근거와 활동이 같은 시점으로 맞는다. 이전 활동은 [ListCaseGithubEventsUseCase].
 * **비공개 가림은 여기서** 한다.
 *
 * 비공개 레포 근거의 상세(제목·주소·레포·번호·CI 실패 이름)를 볼 수 있는 사람은 [GithubCaseAccessPolicy.canSeePrivate].
 * 그 외(PUBLIC Case 를 본 외부인)에게는 종류·상태만 남기고 나머지 필드를 **응답에서 아예 뺀다**.
 * 저장은 원본 그대로 — 가림은 응답을 만들 때만. 레포 정보를 모르면(장부에서 사라짐) 비공개로 간주.
 */
@Service
@ConditionalOnGithubEnabled
class GetCaseGithubEvidenceUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val access: ErrorCaseAccess,
    private val policy: GithubCaseAccessPolicy,
    private val evidence: GithubEvidenceRepositoryPort,
    private val installations: GithubInstallationRepositoryPort,
    private val activity: GithubActivityReader,
) {
    /**
     * 근거·Step 꼬리표·활동을 **한 스냅샷**에서 읽는다 — 기본(READ COMMITTED)은 쿼리마다 그 순간을 읽어서,
     * 사이에 웹훅이 끼면 "근거는 머지 전, 활동은 머지 후" 같은 어긋남이 한 응답 안에서도 생길 수 있다.
     * PostgreSQL 의 읽기 전용 REPEATABLE READ 는 직렬화 실패가 없다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun invoke(caseId: Long, viewerUserId: Long): EvidenceView {
        val errorCase = errorCases.findById(caseId) ?: throw ErrorCaseNotFoundException(caseId)
        access.requireRead(errorCase, viewerUserId)

        val links = evidence.findLinks(caseId).filter { it.isActive }
        val closingIssues = evidence.findClosingIssuesOf(
            links.filter { it.kind == GithubRefKind.PULL_REQUEST }.map { GithubItemRef(it.repoId, it.number) },
        )
        val repoIds = (links.map { it.repoId } + links.mapNotNull { it.viaIssueRepoId } + closingIssues.values.flatten().map { it.repoId }).toSet()
        val repos = installations.findRepositoriesByIds(repoIds).associateBy { it.repoId }
        val activeInstallations = installations.findByIds(repos.values.map { it.installationId }.toSet())
            .filter { it.status == GithubInstallationStatus.ACTIVE }
            .map { it.installationId }.toSet()

        val issues = evidence.findIssues(links.filter { it.kind == GithubRefKind.ISSUE }.map { GithubItemRef(it.repoId, it.number) })
            .associateBy { GithubItemRef(it.repoId, it.number) }
        val pullRequests = evidence.findPullRequests(links.filter { it.kind == GithubRefKind.PULL_REQUEST }.map { GithubItemRef(it.repoId, it.number) })
            .associateBy { GithubItemRef(it.repoId, it.number) }

        // Step 꼬리표 — 떼진 근거의 꼬리표는 떼기 때 지워지지만, 방어적으로 활성 연결 것만.
        val activeLinkIds = links.mapNotNull { it.id }.toSet()
        val stepIdsByLink = evidence.findStepLinks(caseId).filter { it.linkId in activeLinkIds }
            .groupBy({ it.linkId }, { it.stepId })

        val canSeePrivate by lazy { policy.canSeePrivate(errorCase, viewerUserId) }
        fun visible(repoId: Long): Boolean = policy.isVisible(repos[repoId], canSeePrivate)

        val issueLinkRefs = links.filter { it.kind == GithubRefKind.ISSUE }.map { GithubItemRef(it.repoId, it.number) }.toSet()
        val items = links.mapNotNull { link ->
            val ref = GithubItemRef(link.repoId, link.number)
            val repo = repos[link.repoId]
            val stepIds = stepIdsByLink[link.id].orEmpty().sorted()
            when (link.kind) {
                GithubRefKind.ISSUE -> issues[ref]?.let { issueView(link, it, repo, visible(link.repoId)) }
                GithubRefKind.PULL_REQUEST -> pullRequests[ref]?.let {
                    prView(link, it, repo, repos, ::visible, closingIssues[ref].orEmpty(), issueLinkRefs)
                }
            }?.let { link to it.copy(stepIds = stepIds) }
        }

        // 위치(어느 Issue 아래에 보일지)는 출처(linkSource)와 따로 정한다:
        //  ① 경유한 Issue → ② 이 PR 이 닫는 다른 Issue(먼저 연결된 순) 중 이 Case 에 붙어 있고 보이는 것 → ③ 없으면 묶지 않음.
        // PR·Issue 중 하나라도 가려지면 그 관계 자체가 비공개 정보라 묶지 않는다.
        val visibleIssueRefs = items.filter { (link, view) -> link.kind == GithubRefKind.ISSUE && !view.masked }
            .map { (link, _) -> GithubItemRef(link.repoId, link.number) }
        val placed = items.filter { (link, _) -> link.kind == GithubRefKind.PULL_REQUEST }.map { (link, view) ->
            val ref = GithubItemRef(link.repoId, link.number)
            val home = if (view.masked) null
            else link.viaIssueRef()?.takeIf { it in visibleIssueRefs }
                ?: visibleIssueRefs.firstOrNull { it in closingIssues[ref].orEmpty() }
            home to view
        }
        val childrenByIssue = placed.filter { it.first != null }.groupBy({ it.first!! }, { it.second })

        val syncStopped = links.any { link ->
            val repo = repos[link.repoId]
            repo == null || !repo.isActive || repo.installationId !in activeInstallations
        }

        return EvidenceView(
            syncStopped = syncStopped,
            issues = items.filter { (link, _) -> link.kind == GithubRefKind.ISSUE }.map { (link, view) ->
                IssueGroup(view, childrenByIssue[GithubItemRef(link.repoId, link.number)].orEmpty())
            },
            ungroupedPullRequests = placed.filter { it.first == null }.map { it.second },
            maskedCount = GithubRefKind.entries.associateWith { k -> items.count { (_, v) -> v.kind == k && v.masked } },
            activity = activity.page(errorCase, viewerUserId, cursor = null, size = GithubActivityReader.DEFAULT_SIZE),
        )
    }

    private fun CaseGithubLink.viaIssueRef(): GithubItemRef? =
        viaIssueRepoId?.let { r -> viaIssueNumber?.let { n -> GithubItemRef(r, n) } }

    private fun issueView(link: CaseGithubLink, issue: GithubIssueSnapshot, repo: GithubRepository?, visible: Boolean) =
        if (!visible) ItemView.masked(GithubRefKind.ISSUE, issue.state)
        else ItemView(
            linkId = link.id, kind = GithubRefKind.ISSUE, masked = false, state = issue.state,
            repo = repo?.fullName, number = issue.number, isPrivate = repo?.isPrivate, title = issue.title, url = issue.url,
            author = issue.authorLogin, linkSource = link.linkSource, linkedAt = link.linkedAt,
        )

    private fun prView(
        link: CaseGithubLink,
        pr: GithubPullRequestSnapshot,
        repo: GithubRepository?,
        repos: Map<Long, GithubRepository>,
        visible: (Long) -> Boolean,
        closes: List<GithubItemRef>,
        issueLinkRefs: Set<GithubItemRef>,
    ) = if (!visible(link.repoId)) ItemView.masked(GithubRefKind.PULL_REQUEST, pr.state)
    else ItemView(
        linkId = link.id, kind = GithubRefKind.PULL_REQUEST, masked = false, state = pr.state,
        repo = repo?.fullName, number = pr.number, isPrivate = repo?.isPrivate, title = pr.title, url = pr.url,
        author = pr.authorLogin, linkSource = link.linkSource, linkedAt = link.linkedAt,
        isDraft = pr.isDraft,
        // 경유 Issue 가 비공개 레포에 있고 볼 권한이 없으면 그 번호도 비공개 정보 — 빼고 준다.
        viaIssue = link.viaIssueRef()?.takeIf { visible(it.repoId) }?.let { RefView(repos[it.repoId]?.fullName, it.number) },
        // 이 PR 이 지금 닫는 Issue(GitHub 판정 + 비기본 브랜치 키워드). 경유 Issue 가 여기 없으면 "더 이상 닫지 않음".
        closesIssues = closes.filter { visible(it.repoId) }.map { ClosingIssueView(repos[it.repoId]?.fullName, it.number, it in issueLinkRefs) },
        diff = DiffView(pr.additions, pr.deletions, pr.changedFiles),
        ci = pr.ci?.let { CiView(it.conclusion, it.passed, it.total, it.failedNames, "${pr.url}/checks", pr.isCiStale) },
    )


    // ── 조회 결과 ─────────────────────────────────────────────
    data class EvidenceView(
        val syncStopped: Boolean,
        /** 연결 순. 각 Issue 아래에 그 Issue 를 닫는 PR. */
        val issues: List<IssueGroup>,
        /** 어느 Issue 아래에도 묶이지 않은 PR — 출처(직접/자동)와 무관, 붙을 Issue 가 Case 에 없거나 가려진 경우. */
        val ungroupedPullRequests: List<ItemView>,
        val maskedCount: Map<GithubRefKind, Int>,
        /** GitHub 활동 첫 쪽 — 근거와 같은 스냅샷. */
        val activity: GithubActivityReader.ActivityPage,
    )

    data class IssueGroup(val issue: ItemView, val pullRequests: List<ItemView>)

    data class ItemView(
        val linkId: Long?,
        val kind: GithubRefKind,
        val masked: Boolean,
        val state: String,
        val repo: String? = null,
        val number: Int? = null,
        val isPrivate: Boolean? = null,
        val title: String? = null,
        val url: String? = null,
        val author: String? = null,
        val linkSource: CaseGithubLink.LinkSource? = null,
        val linkedAt: Instant? = null,
        val isDraft: Boolean? = null,
        val viaIssue: RefView? = null,
        val closesIssues: List<ClosingIssueView>? = null,
        val diff: DiffView? = null,
        val ci: CiView? = null,
        /** 이 근거가 꼬리표로 붙은 Step id. 가려진 항목에도 내려준다 — Step 번호는 비공개 내용이 아님(P1-e D9). */
        val stepIds: List<Long> = emptyList(),
    ) {
        companion object {
            fun masked(kind: GithubRefKind, state: String) = ItemView(linkId = null, kind = kind, masked = true, state = state)
        }
    }

    data class RefView(val repo: String?, val number: Int)

    /** PR 이 닫는 Issue. [inCase] = 그 Issue 도 이 Case 에 붙어 있음(목록에서 함께 보임). */
    data class ClosingIssueView(val repo: String?, val number: Int, val inCase: Boolean)
    data class DiffView(val additions: Int?, val deletions: Int?, val changedFiles: Int?)
    data class CiView(
        val status: CiSummary.CiConclusion,
        val passed: Int,
        val total: Int,
        val failed: List<String>,
        val url: String,
        /** 최신 CI 가 지금 head 커밋 것이 아님 — 새 커밋의 CI 대기 중. */
        val stale: Boolean,
    )
}
