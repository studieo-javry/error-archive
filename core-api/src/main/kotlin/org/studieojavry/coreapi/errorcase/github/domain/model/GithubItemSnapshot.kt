package org.studieojavry.coreapi.errorcase.github.domain.model

import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import java.time.Instant

/**
 * GitHub Issue 스냅샷. Case 와 독립(같은 Issue 가 여러 Case 에 붙어도 한 행) — Case 가 지워져도 남는다.
 * [sourceUpdatedAt] 보다 오래된 정보는 반영하지 않는다(웹훅은 순서 보장 없음).
 */
class GithubIssueSnapshot(
    val repoId: Long,
    val number: Int,
    var title: String,
    var url: String,
    var state: String,
    var authorLogin: String?,
    var createdAtGh: Instant,
    var closedAtGh: Instant?,
    var sourceUpdatedAt: Instant,
    var deleted: Boolean = false,
) {
    /** [next] 가 더 새롭거나 같을 때만 반영. 반영했으면 true. */
    fun applyIfNewer(next: GithubIssueSnapshot): Boolean {
        if (next.sourceUpdatedAt.isBefore(sourceUpdatedAt)) return false
        title = next.title
        url = next.url
        state = next.state
        authorLogin = next.authorLogin
        closedAtGh = next.closedAtGh
        sourceUpdatedAt = next.sourceUpdatedAt
        deleted = false
        return true
    }

    companion object {
        const val STATE_OPEN = "OPEN"
        const val STATE_CLOSED = "CLOSED"
    }
}

/**
 * GitHub PR 스냅샷 + CI 요약. CI 는 head 커밋 것이 우선이고, head 에 검사가 하나도 없는 머지된 PR 만
 * 머지 커밋([mergeCommitSha]) 의 검사 결과를 대신 보여 준다("머지 후 CI" — push 로만 CI 를 돌리는 레포).
 */
class GithubPullRequestSnapshot(
    val repoId: Long,
    val number: Int,
    var title: String,
    var url: String,
    var state: String,
    var isDraft: Boolean,
    var authorLogin: String?,
    var headSha: String,
    var baseRef: String?,
    var additions: Int?,
    var deletions: Int?,
    var changedFiles: Int?,
    var createdAtGh: Instant,
    var mergedAtGh: Instant?,
    var closedAtGh: Instant?,
    var sourceUpdatedAt: Instant,
    var ci: CiSummary? = null,
    /** 머지 커밋 sha (머지 전·옛 행은 null). */
    var mergeCommitSha: String? = null,
) {
    val isMerged: Boolean get() = state == STATE_MERGED

    /** 지금 CI 요약이 머지 커밋 것("머지 후 CI"). */
    val isMergeCi: Boolean get() = ci.let { it != null && it.sha != headSha && it.sha == mergeCommitSha }

    /** 최신 CI 가 지금 head 커밋 것도 머지 커밋 것도 아니면 "새 커밋 CI 대기 중". */
    val isCiStale: Boolean get() = ci != null && ci?.sha != headSha && !isMergeCi

    fun applyIfNewer(next: GithubPullRequestSnapshot): Boolean {
        if (next.sourceUpdatedAt.isBefore(sourceUpdatedAt)) return false
        title = next.title
        url = next.url
        state = next.state
        isDraft = next.isDraft
        authorLogin = next.authorLogin
        headSha = next.headSha
        baseRef = next.baseRef
        additions = next.additions
        deletions = next.deletions
        changedFiles = next.changedFiles
        mergedAtGh = next.mergedAtGh
        closedAtGh = next.closedAtGh
        sourceUpdatedAt = next.sourceUpdatedAt
        mergeCommitSha = next.mergeCommitSha ?: mergeCommitSha
        return true
    }

    /**
     * CI 요약 갱신. head 커밋 것은 항상 받고, 머지 커밋 것은 head 커밋 CI 가 없을 때만 받는다.
     * 그 밖(이전 커밋의 늦은 결과 등)은 무시(false).
     */
    fun recordCi(summary: CiSummary): Boolean {
        val accept = when (summary.sha) {
            headSha -> true
            mergeCommitSha -> ci?.sha != headSha
            else -> false
        }
        if (accept) ci = summary
        return accept
    }

    /** 닫히지(머지 없이) 않았는데 CI 가 끝나지 않음 — 없음·진행 중·새 커밋 대기. 놓친 웹훅 보충이 CI 를 다시 볼지 정할 때. */
    val isCiUnsettled: Boolean
        get() = state != STATE_CLOSED && (ci == null || isCiStale || ci?.conclusion == CiSummary.CiConclusion.PENDING)

    /** 이 커밋이 이 PR 의 지금 head 또는 머지 커밋인가 — 그 밖의 커밋 CI 는 기록하지 않는다. */
    fun ownsCommit(sha: String): Boolean = sha == headSha || sha == mergeCommitSha

    companion object {
        const val STATE_OPEN = "OPEN"
        const val STATE_MERGED = "MERGED"
        const val STATE_CLOSED = "CLOSED"
    }
}
