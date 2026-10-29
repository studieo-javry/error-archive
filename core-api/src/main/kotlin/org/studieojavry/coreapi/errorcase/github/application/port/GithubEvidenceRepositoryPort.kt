package org.studieojavry.coreapi.errorcase.github.application.port

import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.StepGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubEventCursor
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import java.time.Instant

/** Case 근거 저장소 — Issue/PR 스냅샷, PR↔Issue 장부, Case 연결, 타임라인 이벤트. */
interface GithubEvidenceRepositoryPort {
    fun findIssue(ref: GithubItemRef): GithubIssueSnapshot?
    fun findIssues(refs: Collection<GithubItemRef>): List<GithubIssueSnapshot>
    fun saveIssue(issue: GithubIssueSnapshot)

    fun findPullRequest(ref: GithubItemRef): GithubPullRequestSnapshot?
    fun findPullRequests(refs: Collection<GithubItemRef>): List<GithubPullRequestSnapshot>
    fun savePullRequest(pr: GithubPullRequestSnapshot)

    /** 이 PR 이 닫는 Issue 목록을 [source] 출처분만 통째로 교체. */
    fun replaceClosingRefs(pr: GithubItemRef, issues: Collection<GithubItemRef>, source: ClosingRefSource)

    /** 여러 PR 각각이 닫는 Issue(출처 무관, 중복 제거). 근거 조회에서 PR 을 어느 Issue 아래에 둘지 정할 때 쓴다. */
    fun findClosingIssuesOf(prs: Collection<GithubItemRef>): Map<GithubItemRef, List<GithubItemRef>>

    fun findLink(caseId: Long, kind: GithubRefKind, ref: GithubItemRef): CaseGithubLink?
    fun findLinkById(linkId: Long): CaseGithubLink?
    fun findLinks(caseId: Long): List<CaseGithubLink>
    fun saveLink(link: CaseGithubLink): CaseGithubLink

    /** 같은 (case, dedupeKey) 가 있으면 무시 — 단 [CaseGithubEvent.replaceable] 이면 최신 내용으로 덮어씀. */
    fun recordEvent(event: CaseGithubEvent)

    /** 타임라인 한 쪽 — 최신순(occurredAt↓, id↓). [before] 가 있으면 그보다 오래된 것만. */
    fun findEventsBefore(caseId: Long, before: GithubEventCursor?, limit: Int): List<CaseGithubEvent>
    fun countEvents(caseId: Long): Long

    // ── Step ↔ 근거 꼬리표 ──
    fun findStepLinks(caseId: Long): List<StepGithubLink>

    /** 이 Step 의 꼬리표를 [linkIds] 로 통째로 맞춤. 유지되는 꼬리표는 출처·시각을 그대로 둔다. */
    fun replaceStepLinks(caseId: Long, stepId: Long, linkIds: Collection<Long>, userId: Long, now: Instant)

    /** Case 에서 근거를 떼면 그 근거의 Step 꼬리표도 모두 지운다(다시 붙여도 복구하지 않음). */
    fun deleteStepLinksByLinkId(linkId: Long)

    enum class ClosingRefSource { GITHUB, KEYWORD }
}

/**
 * Case 삭제 시 GitHub 연결·타임라인 정리. **GitHub 기능 스위치와 무관하게 항상 등록** —
 * 켰다가 끈 뒤에 Case 를 지워도 행이 남지 않도록.
 */
interface CaseGithubCleanupPort {
    fun deleteAllByCaseId(caseId: Long)
}

/** Step 삭제 시 그 Step 의 GitHub 근거 꼬리표 정리. [CaseGithubCleanupPort] 와 같은 이유로 스위치와 무관하게 등록. */
interface StepGithubCleanupPort {
    fun deleteAllByStepId(stepId: Long)
}
