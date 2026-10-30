package org.studieojavry.coreapi.errorcase.github.domain.model

import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import java.time.Instant

/**
 * Case ↔ Issue/PR 연결. "관련 없음"(떼기)은 행을 지우지 않고 [detachedAt] 만 표시한다 —
 * 그래야 같은 PR 웹훅이 다시 와도 자동으로 재부착하지 않는다. 같은 주소를 사람이 다시 붙이면 [reattach].
 */
class CaseGithubLink(
    val id: Long?,
    val caseId: Long,
    val kind: GithubRefKind,
    val repoId: Long,
    val number: Int,
    var linkSource: LinkSource,
    var viaIssueRepoId: Long?,
    var viaIssueNumber: Int?,
    var linkedByUserId: Long?,
    var linkedAt: Instant,
    var detachedAt: Instant?,
    var detachedByUserId: Long?,
) {
    enum class LinkSource { MANUAL, IMPORTED, VIA_ISSUE }

    val isActive: Boolean get() = detachedAt == null

    fun detach(userId: Long, at: Instant = Instant.now()) {
        if (detachedAt != null) return
        detachedAt = at
        detachedByUserId = userId
    }

    /** 사람이 다시 붙임 — 출처도 MANUAL 로 바꾼다(이제 Issue 경유가 아니라 직접 고른 근거). */
    fun reattach(userId: Long, at: Instant = Instant.now()) {
        detachedAt = null
        detachedByUserId = null
        linkSource = LinkSource.MANUAL
        viaIssueRepoId = null
        viaIssueNumber = null
        linkedByUserId = userId
        linkedAt = at
    }

    companion object {
        fun manual(caseId: Long, kind: GithubRefKind, repoId: Long, number: Int, userId: Long, at: Instant = Instant.now()) =
            CaseGithubLink(null, caseId, kind, repoId, number, LinkSource.MANUAL, null, null, userId, at, null, null)
    }
}
