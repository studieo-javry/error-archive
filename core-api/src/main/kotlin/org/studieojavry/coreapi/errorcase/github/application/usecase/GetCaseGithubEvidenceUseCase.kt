package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Service
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import java.time.Instant

/**
 * Case 의 GitHub 근거(연결된 Issue·PR·CI) + 근거 타임라인 조회. **비공개 가림은 여기서** 한다.
 *
 * 비공개 레포 근거의 상세(제목·주소·레포·번호·CI 실패 이름)는
 *  - 워크스페이스 Case: 그 워크스페이스의 **현재** 멤버(역할 무관)에게만. 주인이어도 탈퇴했으면 가린다
 *    — 레포를 그 워크스페이스에 공유한 건 관리자이고, 공유 대상은 "지금 팀원"이기 때문.
 *  - 개인 Case: 주인에게만.
 * 그 외(PUBLIC Case 를 본 외부인)에게는 종류·상태만 남기고 나머지 필드를 **응답에서 아예 뺀다**.
 * 저장은 원본 그대로 — 가림은 응답을 만들 때만. 레포 정보를 모르면(장부에서 사라짐) 비공개로 간주.
 */
@Service
@ConditionalOnGithubEnabled
class GetCaseGithubEvidenceUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val access: ErrorCaseAccess,
    private val workspaceQuery: WorkspaceQueryPort,
    private val evidence: GithubEvidenceRepositoryPort,
    private val installations: GithubInstallationRepositoryPort,
) {
    fun invoke(caseId: Long, viewerUserId: Long): EvidenceView {
        val errorCase = errorCases.findById(caseId) ?: throw ErrorCaseNotFoundException(caseId)
        access.requireRead(errorCase, viewerUserId)

        val links = evidence.findLinks(caseId).filter { it.isActive }
        val events = evidence.findEvents(caseId)
        val repoIds = (links.map { it.repoId } + events.map { it.repoId } + links.mapNotNull { it.viaIssueRepoId }).toSet()
        val repos = installations.findRepositoriesByIds(repoIds).associateBy { it.repoId }
        val activeInstallations = installations.findByIds(repos.values.map { it.installationId }.toSet())
            .filter { it.status == GithubInstallationStatus.ACTIVE }
            .map { it.installationId }.toSet()

        val issues = evidence.findIssues(links.filter { it.kind == GithubRefKind.ISSUE }.map { GithubItemRef(it.repoId, it.number) })
            .associateBy { GithubItemRef(it.repoId, it.number) }
        val pullRequests = evidence.findPullRequests(links.filter { it.kind == GithubRefKind.PULL_REQUEST }.map { GithubItemRef(it.repoId, it.number) })
            .associateBy { GithubItemRef(it.repoId, it.number) }

        val canSeePrivate by lazy { canSeePrivate(errorCase, viewerUserId) }
        fun visible(repoId: Long): Boolean = repos[repoId]?.isPrivate == false || canSeePrivate

        val items = links.mapNotNull { link ->
            val ref = GithubItemRef(link.repoId, link.number)
            val repo = repos[link.repoId]
            when (link.kind) {
                GithubRefKind.ISSUE -> issues[ref]?.let { issueView(link, it, repo, visible(link.repoId)) }
                GithubRefKind.PULL_REQUEST -> pullRequests[ref]?.let { prView(link, it, repo, repos, visible(link.repoId)) }
            }
        }

        val syncStopped = links.any { link ->
            val repo = repos[link.repoId]
            repo == null || !repo.isActive || repo.installationId !in activeInstallations
        }

        return EvidenceView(
            syncStopped = syncStopped,
            items = items,
            events = events.map { eventView(it, repos[it.repoId], visible(it.repoId)) },
            maskedCount = GithubRefKind.entries.associateWith { k -> items.count { it.kind == k && it.masked } },
        )
    }

    private fun canSeePrivate(errorCase: ErrorCase, viewerUserId: Long): Boolean {
        val wsId = errorCase.meta.workspaceId ?: return errorCase.ownerUserId == viewerUserId
        return workspaceQuery.getViewerRole(viewerUserId, wsId) != null
    }

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
        visible: Boolean,
    ) = if (!visible) ItemView.masked(GithubRefKind.PULL_REQUEST, pr.state)
    else ItemView(
        linkId = link.id, kind = GithubRefKind.PULL_REQUEST, masked = false, state = pr.state,
        repo = repo?.fullName, number = pr.number, isPrivate = repo?.isPrivate, title = pr.title, url = pr.url,
        author = pr.authorLogin, linkSource = link.linkSource, linkedAt = link.linkedAt,
        isDraft = pr.isDraft,
        viaIssue = link.viaIssueRepoId?.let { r -> link.viaIssueNumber?.let { n -> RefView(repos[r]?.fullName, n) } },
        diff = DiffView(pr.additions, pr.deletions, pr.changedFiles),
        ci = pr.ci?.let { CiView(it.conclusion, it.passed, it.total, it.failedNames, "${pr.url}/checks", pr.isCiStale) },
    )

    private fun eventView(e: CaseGithubEvent, repo: GithubRepository?, visible: Boolean): EventView =
        if (visible) {
            EventView(e.type, e.occurredAt, e.kind, repo?.fullName, e.number, masked = false, data = e.data)
        } else {
            // 외부인에게는 "언제 무슨 일이 있었나"만. CI 는 결과(통과/실패)까지만, 실패 job 이름·sha 는 제외.
            val data = if (e.type == CaseGithubEvent.Type.CI_COMPLETED) e.data.filterKeys { it == "conclusion" } else emptyMap()
            EventView(e.type, e.occurredAt, e.kind, null, null, masked = true, data = data)
        }

    // ── 조회 결과 ─────────────────────────────────────────────
    data class EvidenceView(
        val syncStopped: Boolean,
        val items: List<ItemView>,
        val events: List<EventView>,
        val maskedCount: Map<GithubRefKind, Int>,
    )

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
        val diff: DiffView? = null,
        val ci: CiView? = null,
    ) {
        companion object {
            fun masked(kind: GithubRefKind, state: String) = ItemView(linkId = null, kind = kind, masked = true, state = state)
        }
    }

    data class RefView(val repo: String?, val number: Int)
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

    data class EventView(
        val type: CaseGithubEvent.Type,
        val occurredAt: Instant,
        val kind: GithubRefKind,
        val repo: String?,
        val number: Int?,
        val masked: Boolean,
        val data: Map<String, Any?>,
    )
}
