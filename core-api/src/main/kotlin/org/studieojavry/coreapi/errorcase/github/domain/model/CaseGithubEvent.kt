package org.studieojavry.coreapi.errorcase.github.domain.model

import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import java.time.Instant

/**
 * Case 근거 타임라인 한 줄. [dedupeKey] 가 (case, 사건) 단위 중복 방지 키 — 생성 규칙은 이 파일의 팩토리에만 둔다.
 * CI 는 커밋(sha)당 한 줄이고, CI 앱이 여러 개라 결과가 여러 번 와도 같은 키를 최신 집계로 덮어쓴다([replaceable]).
 */
class CaseGithubEvent(
    val caseId: Long,
    val type: Type,
    val kind: GithubRefKind,
    val repoId: Long,
    val number: Int,
    val occurredAt: Instant,
    val data: Map<String, Any?>,
    val dedupeKey: String,
    val replaceable: Boolean = false,
    /** 저장된 뒤에만 있음 — 타임라인 커서의 동순위 구분용. */
    val id: Long? = null,
) {
    enum class Type {
        ISSUE_LINKED, ISSUE_CLOSED, ISSUE_REOPENED,
        PR_LINKED, PR_OPENED, PR_MERGED, PR_CLOSED, PR_REOPENED,
        CI_COMPLETED, REF_REMOVED, LINK_DETACHED,
    }

    companion object {
        private fun ref(repoId: Long, number: Int) = "$repoId#$number"

        fun linked(
            caseId: Long, kind: GithubRefKind, repoId: Long, number: Int, source: CaseGithubLink.LinkSource, byUserId: Long?, at: Instant,
            viaIssue: GithubItemRef? = null,
        ) =
            CaseGithubEvent(
                caseId, if (kind == GithubRefKind.ISSUE) Type.ISSUE_LINKED else Type.PR_LINKED, kind, repoId, number, at,
                buildMap {
                    put("source", source.name)
                    put("byUserId", byUserId)
                    // 관련 Issue 키 이름은 조회 쪽(ListCaseGithubEventsUseCase)과 약속 — 레포 이름으로 바꾸고 가림 처리함
                    viaIssue?.let { put("issueRepoId", it.repoId); put("issueNumber", it.number) }
                },
                // 떼었다 다시 붙이면 새 줄이 생기도록 시각 포함
                "LINKED:$kind:${ref(repoId, number)}@${at.toEpochMilli()}",
            )

        fun detached(caseId: Long, kind: GithubRefKind, repoId: Long, number: Int, byUserId: Long, at: Instant) =
            CaseGithubEvent(
                caseId, Type.LINK_DETACHED, kind, repoId, number, at, mapOf("byUserId" to byUserId),
                "DETACHED:$kind:${ref(repoId, number)}@${at.toEpochMilli()}",
            )

        fun issueClosed(caseId: Long, issue: GithubIssueSnapshot) = CaseGithubEvent(
            caseId, Type.ISSUE_CLOSED, GithubRefKind.ISSUE, issue.repoId, issue.number, issue.closedAtGh ?: issue.sourceUpdatedAt,
            emptyMap(), "ISSUE_CLOSED:${ref(issue.repoId, issue.number)}@${(issue.closedAtGh ?: issue.sourceUpdatedAt).toEpochMilli()}",
        )

        fun prOpened(caseId: Long, pr: GithubPullRequestSnapshot) = CaseGithubEvent(
            caseId, Type.PR_OPENED, GithubRefKind.PULL_REQUEST, pr.repoId, pr.number, pr.createdAtGh,
            mapOf("author" to pr.authorLogin), "PR_OPENED:${ref(pr.repoId, pr.number)}",
        )

        fun prMerged(caseId: Long, pr: GithubPullRequestSnapshot) = CaseGithubEvent(
            caseId, Type.PR_MERGED, GithubRefKind.PULL_REQUEST, pr.repoId, pr.number, pr.mergedAtGh ?: pr.sourceUpdatedAt,
            mapOf("ciConclusion" to pr.ci?.conclusion?.name), "PR_MERGED:${ref(pr.repoId, pr.number)}",
        )

        fun prClosed(caseId: Long, pr: GithubPullRequestSnapshot): CaseGithubEvent {
            val at = pr.closedAtGh ?: pr.sourceUpdatedAt
            return CaseGithubEvent(
                caseId, Type.PR_CLOSED, GithubRefKind.PULL_REQUEST, pr.repoId, pr.number, at,
                emptyMap(), "PR_CLOSED:${ref(pr.repoId, pr.number)}@${at.toEpochMilli()}",
            )
        }

        fun prReopened(caseId: Long, pr: GithubPullRequestSnapshot, at: Instant) = CaseGithubEvent(
            caseId, Type.PR_REOPENED, GithubRefKind.PULL_REQUEST, pr.repoId, pr.number, at,
            emptyMap(), "PR_REOPENED:${ref(pr.repoId, pr.number)}@${at.toEpochMilli()}",
        )

        /** PR 이 더 이상 이 Issue 를 닫지 않음(본문에서 키워드 삭제 등). 연결은 그대로 두고 사실만 남긴다. */
        fun refRemoved(caseId: Long, pr: GithubPullRequestSnapshot, issue: GithubItemRef, at: Instant) = CaseGithubEvent(
            caseId, Type.REF_REMOVED, GithubRefKind.PULL_REQUEST, pr.repoId, pr.number, at,
            mapOf("issueRepoId" to issue.repoId, "issueNumber" to issue.number),
            "REF_REMOVED:${ref(pr.repoId, pr.number)}>${ref(issue.repoId, issue.number)}@${at.toEpochMilli()}",
        )

        fun ciCompleted(caseId: Long, repoId: Long, prNumber: Int, ci: CiSummary, at: Instant) = CaseGithubEvent(
            caseId, Type.CI_COMPLETED, GithubRefKind.PULL_REQUEST, repoId, prNumber, ci.completedAt ?: at,
            mapOf(
                "sha" to ci.sha, "conclusion" to ci.conclusion.name, "total" to ci.total,
                "passed" to ci.passed, "failed" to ci.failedNames,
            ),
            "CI:$repoId:${ci.sha}",
            replaceable = true,
        )
    }
}
