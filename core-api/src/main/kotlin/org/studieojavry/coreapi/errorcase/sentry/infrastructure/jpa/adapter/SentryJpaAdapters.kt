package org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.sentry.application.port.SentryIssueCandidateRepositoryPort
import org.studieojavry.coreapi.errorcase.sentry.application.port.SentryProjectLinkPort
import org.studieojavry.coreapi.errorcase.sentry.domain.model.SentryIssueCandidate
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa.SentryIssueCaseJpaRepository
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa.SentryProjectLinkJpaRepository
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa.entity.SentryIssueCaseEntity

@Component
class SentryProjectLinkJpaAdapter(
    private val jpa: SentryProjectLinkJpaRepository,
) : SentryProjectLinkPort {
    override fun findByProject(projectSlug: String): SentryProjectLinkPort.Link? =
        jpa.findBySentryProject(projectSlug)?.let {
            SentryProjectLinkPort.Link(workspaceId = it.workspaceId, createdByUserId = it.createdBy)
        }
}

@Component
class SentryIssueCandidateJpaAdapter(
    private val jpa: SentryIssueCaseJpaRepository,
) : SentryIssueCandidateRepositoryPort {

    override fun find(issueId: String): SentryIssueCandidate? =
        jpa.findById(issueId).orElse(null)?.toDomain()

    override fun save(candidate: SentryIssueCandidate) {
        jpa.save(candidate.toEntity())
    }

    private fun SentryIssueCaseEntity.toDomain(): SentryIssueCandidate = SentryIssueCandidate.reconstitute(
        issueId = sentryIssueId,
        workspaceId = workspaceId,
        shortId = shortId,
        fingerprintKey = fingerprintKey,
        title = title,
        culprit = culprit,
        issueType = issueType,
        level = level,
        permalink = permalink,
        status = status,
        caseId = caseId,
        occurrences = occurrences,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
    )

    private fun SentryIssueCandidate.toEntity(): SentryIssueCaseEntity = SentryIssueCaseEntity(
        sentryIssueId = issueId,
        workspaceId = workspaceId,
        shortId = shortId,
        fingerprintKey = fingerprintKey,
        title = title,
        culprit = culprit,
        issueType = issueType,
        level = level,
        permalink = permalink,
        status = status,
        caseId = caseId,
        occurrences = occurrences,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
    )
}
