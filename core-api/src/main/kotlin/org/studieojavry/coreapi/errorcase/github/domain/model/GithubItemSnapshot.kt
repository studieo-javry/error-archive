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
}

/** GitHub PR 스냅샷 + 최신 head 커밋의 CI 요약. */
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
) {
    val isMerged: Boolean get() = state == STATE_MERGED

    /** 최신 CI 가 지금 head 커밋 것이 아니면 "새 커밋 CI 대기 중". */
    val isCiStale: Boolean get() = ci != null && ci?.sha != headSha

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
        return true
    }

    /** head 커밋의 CI 만 최신 요약으로 받는다. 이전 커밋의 늦은 결과는 무시(false). */
    fun recordCi(summary: CiSummary): Boolean {
        if (summary.sha != headSha) return false
        ci = summary
        return true
    }

    companion object {
        const val STATE_MERGED = "MERGED"
        const val STATE_CLOSED = "CLOSED"
    }
}
