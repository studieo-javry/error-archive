package org.studieojavry.coreapi.errorcase.github.application.port

import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind

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

    fun findLink(caseId: Long, kind: GithubRefKind, ref: GithubItemRef): CaseGithubLink?
    fun findLinkById(linkId: Long): CaseGithubLink?
    fun findLinks(caseId: Long): List<CaseGithubLink>
    fun saveLink(link: CaseGithubLink): CaseGithubLink

    /** 같은 (case, dedupeKey) 가 있으면 무시 — 단 [CaseGithubEvent.replaceable] 이면 최신 내용으로 덮어씀. */
    fun recordEvent(event: CaseGithubEvent)
    fun findEvents(caseId: Long): List<CaseGithubEvent>

    enum class ClosingRefSource { GITHUB, KEYWORD }
}

/**
 * Case 삭제 시 GitHub 연결·타임라인 정리. **GitHub 기능 스위치와 무관하게 항상 등록** —
 * 켰다가 끈 뒤에 Case 를 지워도 행이 남지 않도록.
 */
interface CaseGithubCleanupPort {
    fun deleteAllByCaseId(caseId: Long)
}
