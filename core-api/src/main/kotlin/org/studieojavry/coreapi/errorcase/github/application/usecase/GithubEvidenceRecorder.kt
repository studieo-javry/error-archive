package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort.ClosingRefSource
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink.LinkSource
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import java.time.Instant

/**
 * GitHub 근거 기록 공용 루틴 — 수동 연결·Issue 로 Case 생성·소급 부착(이후 웹훅 처리)이 같은 규칙으로 쓰게 한곳에 둔다.
 * 트랜잭션은 호출자가 연다(이 클래스는 DB 쓰기만, GitHub 호출 없음).
 */
@Component
class GithubEvidenceRecorder(
    private val evidence: GithubEvidenceRepositoryPort,
) {
    /** 가져온 Issue 를 스냅샷에 반영(더 새로울 때만)하고 저장된 최신 상태를 돌려준다. */
    fun saveIssue(fetched: GithubIssueSnapshot): GithubIssueSnapshot {
        val issue = evidence.findIssue(GithubItemRef(fetched.repoId, fetched.number))
            ?.also { it.applyIfNewer(fetched) } ?: fetched
        evidence.saveIssue(issue)
        return issue
    }

    /** PR 스냅샷 + head 커밋 CI + 장부(GitHub 판정 닫는 Issue)를 반영. */
    fun savePullRequest(item: GithubAppApiPort.PullRequestItem, ci: CiSummary?): GithubPullRequestSnapshot {
        val fetched = item.snapshot
        val ref = GithubItemRef(fetched.repoId, fetched.number)
        val pr = evidence.findPullRequest(ref)?.also { it.applyIfNewer(fetched) } ?: fetched
        ci?.let { pr.recordCi(it) }
        evidence.savePullRequest(pr)
        evidence.replaceClosingRefs(ref, item.closingIssues, ClosingRefSource.GITHUB)
        return pr
    }

    fun issueStateEvents(caseId: Long, issue: GithubIssueSnapshot): List<CaseGithubEvent> =
        if (issue.state == "CLOSED") listOf(CaseGithubEvent.issueClosed(caseId, issue)) else emptyList()

    /** PR 의 지금 상태를 타임라인 사건들로 — 열림, 머지/닫힘, 최신 CI. dedupe 로 몇 번 불러도 한 번만 남는다. */
    fun pullRequestStateEvents(caseId: Long, pr: GithubPullRequestSnapshot, now: Instant): List<CaseGithubEvent> = buildList {
        add(CaseGithubEvent.prOpened(caseId, pr))
        when {
            pr.isMerged -> add(CaseGithubEvent.prMerged(caseId, pr))
            pr.state == GithubPullRequestSnapshot.STATE_CLOSED -> add(CaseGithubEvent.prClosed(caseId, pr))
        }
        pr.ci?.let { add(CaseGithubEvent.ciCompleted(caseId, pr.repoId, pr.number, it, now)) }
    }

    data class AttachOutcome(val link: CaseGithubLink?, val created: Boolean)

    /**
     * Case 에 붙인다.
     *  - 이미 붙어 있음 → 그대로(created=false)
     *  - 뗐던 것 → 사람이 붙이면(MANUAL/IMPORTED) 복구, **자동(VIA_ISSUE)이면 붙이지 않음**(link=null)
     *  - 처음 → 새로 붙이고 "연결됨" 이벤트
     * 상태 이벤트([stateEvents])는 붙어 있는 경우에만 기록한다.
     */
    fun attach(
        caseId: Long,
        kind: GithubRefKind,
        ref: GithubItemRef,
        source: LinkSource,
        userId: Long?,
        stateEvents: List<CaseGithubEvent>,
        viaIssue: GithubItemRef? = null,
        now: Instant = Instant.now(),
    ): AttachOutcome {
        val existing = evidence.findLink(caseId, kind, ref)
        val outcome = when {
            existing != null && existing.isActive -> AttachOutcome(existing, created = false)
            existing != null && source == LinkSource.VIA_ISSUE -> return AttachOutcome(null, created = false)
            existing != null -> {
                existing.reattach(requireNotNull(userId), now)
                AttachOutcome(evidence.saveLink(existing), created = true)
            }
            else -> AttachOutcome(
                evidence.saveLink(CaseGithubLink.create(caseId, kind, ref.repoId, ref.number, source, userId, viaIssue, now)),
                created = true,
            )
        }
        if (outcome.created) {
            evidence.recordEvent(CaseGithubEvent.linked(caseId, kind, ref.repoId, ref.number, outcome.link!!.linkSource, userId, now, viaIssue))
        }
        stateEvents.forEach(evidence::recordEvent)
        return outcome
    }
}
