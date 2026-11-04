package org.studieojavry.coreapi.errorcase.sentry.domain.model

import org.studieojavry.coreapi.errorcase.sentry.domain.model.vo.NormalizedSentryIssue
import org.studieojavry.coreapi.errorcase.sentry.domain.model.vo.SentryCandidateStatus
import java.time.Instant

/**
 * Sentry 이슈로부터 만든 Case 후보 + 이슈↔Case 매핑. (`sentry_issue_case`)
 *
 * 신규 감지 = [propose] (PROPOSED), 같은 이슈 재수신 = [recordOccurrence], 연결된 Case 가 있는 상태에서
 * 재발 = [proposeReopen] (REOPEN_PROPOSED). 무조건 Case 를 만들지 않고 후보로 둔다 — 승격은 사용자 몫.
 */
class SentryIssueCandidate private constructor(
    val issueId: String,
    val workspaceId: Long,
    var shortId: String?,
    var fingerprintKey: String,
    var title: String,
    var culprit: String?,
    var issueType: String?,
    var level: String,
    var permalink: String?,
    var status: SentryCandidateStatus,
    var caseId: Long?,
    var occurrences: Int,
    val firstSeenAt: Instant,
    var lastSeenAt: Instant,
) {
    /** 같은 이슈가 다시 감지됨 — 발생 이력만 누적(상태·연결 유지). */
    fun recordOccurrence(at: Instant) {
        occurrences += 1
        lastSeenAt = at
    }

    /** 연결된 Case 가 있는 상태에서 재발 — 재오픈 후보로. 연결이 없으면 발생 이력만 쌓는다. */
    fun proposeReopen(at: Instant) {
        if (caseId != null) status = SentryCandidateStatus.REOPEN_PROPOSED
        recordOccurrence(at)
    }

    companion object {
        /** 신규 후보(PROPOSED, 발생 1회). */
        fun propose(issue: NormalizedSentryIssue, workspaceId: Long, at: Instant): SentryIssueCandidate =
            SentryIssueCandidate(
                issueId = issue.issueId,
                workspaceId = workspaceId,
                shortId = issue.shortId,
                fingerprintKey = issue.fingerprintKey,
                title = issue.title,
                culprit = issue.culprit,
                issueType = issue.issueType,
                level = issue.level,
                permalink = issue.permalink,
                status = SentryCandidateStatus.PROPOSED,
                caseId = null,
                occurrences = 1,
                firstSeenAt = at,
                lastSeenAt = at,
            )

        /** 영속 상태에서 복원. */
        @Suppress("LongParameterList")
        fun reconstitute(
            issueId: String,
            workspaceId: Long,
            shortId: String?,
            fingerprintKey: String,
            title: String,
            culprit: String?,
            issueType: String?,
            level: String,
            permalink: String?,
            status: SentryCandidateStatus,
            caseId: Long?,
            occurrences: Int,
            firstSeenAt: Instant,
            lastSeenAt: Instant,
        ): SentryIssueCandidate = SentryIssueCandidate(
            issueId, workspaceId, shortId, fingerprintKey, title, culprit, issueType,
            level, permalink, status, caseId, occurrences, firstSeenAt, lastSeenAt,
        )
    }
}
