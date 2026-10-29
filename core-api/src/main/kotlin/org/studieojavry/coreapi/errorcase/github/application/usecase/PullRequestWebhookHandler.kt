package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort.ClosingRefSource
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink.LinkSource
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.ClosingKeywords
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * `pull_request` 웹훅 (P1-c) — PR 이 올라오거나 바뀌면 그 PR 이 닫는 Issue 가 붙은 Case 에 **알아서** 붙이고 상태 변화를 기록한다.
 *
 * 1. 저장소·설치가 활성이 아니면 건너뜀
 * 2. 사전 필터: 이미 어떤 Case 에 연결됐던 PR / 장부에 있던 PR / 제목·본문에 닫는 키워드 — 셋 다 아니면 GitHub 를 부르지 않음
 * 3. GitHub 에서 PR 을 다시 조회("웹훅은 알림, 진실은 API" — 순서 뒤섞임·누락 대비)
 * 4. 스냅샷 + 장부 갱신. 장부 = GitHub 판정(closingIssuesReferences) ∪ **기본 브랜치가 아닌 PR 이면 우리 키워드 판정(KEYWORD)**
 * 5. 닫는 Issue 가 붙은 Case 마다 VIA_ISSUE 로 부착 — 범위 불일치·"관련 없음"으로 뗀 PR 은 건너뜀
 * 6. 장부에서 빠진 Issue 경유로 붙어 있던 Case 에 REF_REMOVED(연결은 유지)
 * 7. 붙어 있는 모든 Case 에 상태 기록(열림·머지·닫힘·다시 열림). 새 커밋(synchronize)은 head sha 만 갱신 — CI 는 P1-d
 *
 * inbox 처리기의 건별 트랜잭션 안에서 실행된다(처리 + DONE 표시를 함께 커밋). GitHub 조회 실패는 예외로 올려 inbox 재시도에 맡긴다.
 */
@Component
@ConditionalOnGithubEnabled
class PullRequestWebhookHandler(
    private val installations: GithubInstallationRepositoryPort,
    private val githubApi: GithubAppApiPort,
    private val evidence: GithubEvidenceRepositoryPort,
    private val recorder: GithubEvidenceRecorder,
    private val scopeGuard: GithubScopeGuard,
    private val errorCases: ErrorCaseRepositoryPort,
) {
    private val log = KotlinLogging.logger {}

    /** @return 처리했으면 true, 관심 없는 이벤트면 false(SKIPPED) */
    fun handle(action: String?, installationId: Long?, root: JsonNode): Boolean {
        if (action !in HANDLED_ACTIONS) return false
        val prNode = root.path("pull_request")
        val repo = installations.findRepository(root.path("repository").path("id").asLong())
            ?.takeIf { it.isActive } ?: return false
        if (installationId != null && installationId != repo.installationId) return false
        val installation = installations.findById(repo.installationId)
            ?.takeIf { it.status == GithubInstallationStatus.ACTIVE } ?: return false

        val number = prNode.path("number").asInt()
        val prRef = GithubItemRef(repo.repoId, number)
        val ledgerBefore = evidence.findClosingIssues(prRef).toSet()
        val interesting = evidence.hasAnyLink(GithubRefKind.PULL_REQUEST, prRef) ||
            ledgerBefore.isNotEmpty() ||
            ClosingKeywords.contains(prNode.path("title").textOrNull()) ||
            ClosingKeywords.contains(prNode.path("body").textOrNull())
        if (!interesting) return false

        val owner = repo.fullName.substringBefore('/')
        val name = repo.fullName.substringAfter('/')
        val item = githubApi.fetchItem(installation.installationId, owner, name, number)
            as? GithubAppApiPort.PullRequestItem ?: return false
        val now = Instant.now()

        val pr = recorder.savePullRequest(item, ci = null)
        val keywordRefs = keywordClosingIssues(item, repo.repoId, repo.fullName)
        evidence.replaceClosingRefs(prRef, keywordRefs, ClosingRefSource.KEYWORD)
        val closing = (item.closingIssues + keywordRefs).toSet()

        var attached = 0
        for (issue in closing) {
            for (caseId in evidence.findActiveLinkedCaseIds(GithubRefKind.ISSUE, issue)) {
                val errorCase = errorCases.findById(caseId) ?: continue
                if (!scopeGuard.isSameScope(installation, errorCase)) {
                    log.info { "[github] auto-attach skipped (scope): case=$caseId pr=${repo.fullName}#$number" }
                    continue
                }
                val outcome = recorder.attach(
                    caseId, GithubRefKind.PULL_REQUEST, prRef, LinkSource.VIA_ISSUE, userId = null,
                    stateEvents = emptyList(), viaIssue = issue, now = now,
                )
                if (outcome.created) attached++
            }
        }

        val removed = ledgerBefore - closing
        val reopenedAt = if (action == "reopened") prNode.path("updated_at").textOrNull()?.let(Instant::parse) ?: now else null
        val linked = evidence.findActiveLinks(GithubRefKind.PULL_REQUEST, prRef)
        for (link in linked) {
            val via = link.viaIssueRepoId?.let { r -> link.viaIssueNumber?.let { GithubItemRef(r, it) } }
            if (via != null && via in removed) evidence.recordEvent(CaseGithubEvent.refRemoved(link.caseId, pr, via, now))
            recorder.pullRequestStateEvents(link.caseId, pr, now).forEach(evidence::recordEvent)
            reopenedAt?.let { evidence.recordEvent(CaseGithubEvent.prReopened(link.caseId, pr, it)) }
        }

        log.info {
            "[github] pull_request $action pr=${repo.fullName}#$number closing=${closing.size} " +
                "attached=$attached linkedCases=${linked.size} removedRefs=${removed.size}"
        }
        return true
    }

    /**
     * 기본 브랜치가 아닌 PR 만 우리 키워드로 판정(GitHub 가 안 쳐 주는 경우 보충). 기본 브랜치면 빈 목록 → 예전 KEYWORD 장부 정리.
     * `owner/repo#N` 은 우리 연결에 있는 저장소만(모르는 저장소의 Issue 는 어차피 붙일 Case 가 없다).
     */
    private fun keywordClosingIssues(item: GithubAppApiPort.PullRequestItem, repoId: Long, fullName: String): List<GithubItemRef> {
        val base = item.snapshot.baseRef
        val default = item.defaultBranch
        if (base == null || default == null || base == default) return emptyList()
        val text = listOfNotNull(item.snapshot.title, item.body).joinToString("\n")
        return ClosingKeywords.parse(text, fullName).mapNotNull { ref ->
            if (ref.fullName.equals(fullName, ignoreCase = true)) GithubItemRef(repoId, ref.number)
            else installations.findActiveRepositoryByFullName(ref.fullName)?.let { GithubItemRef(it.repoId, ref.number) }
        }.distinct()
    }

    private fun JsonNode.textOrNull(): String? = takeIf { it.isString }?.asString()

    companion object {
        val HANDLED_ACTIONS = setOf(
            "opened", "edited", "reopened", "synchronize", "closed", "ready_for_review", "converted_to_draft",
        )
    }
}
