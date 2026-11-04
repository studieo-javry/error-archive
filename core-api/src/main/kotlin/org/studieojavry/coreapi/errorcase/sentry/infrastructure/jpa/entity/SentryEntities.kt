package org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.sentry.domain.model.vo.SentryCandidateStatus
import java.time.Instant

/** Sentry 프로젝트 ↔ 워크스페이스 연결. (`sentry_project_link`) */
@Entity
@Table(name = "sentry_project_link")
class SentryProjectLinkEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    var workspaceId: Long,

    @Column(name = "sentry_org", length = 128)
    var sentryOrg: String?,

    @Column(name = "sentry_project", nullable = false, length = 128)
    var sentryProject: String,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

/** Sentry 이슈 ↔ Case 후보/매핑. (`sentry_issue_case`) */
@Entity
@Table(name = "sentry_issue_case")
class SentryIssueCaseEntity(
    @Id
    @Column(name = "sentry_issue_id", length = 64)
    var sentryIssueId: String,

    @Column(name = "workspace_id", nullable = false)
    var workspaceId: Long,

    @Column(name = "short_id", length = 64)
    var shortId: String?,

    @Column(name = "fingerprint_key", nullable = false, length = 400)
    var fingerprintKey: String,

    @Column(name = "title", columnDefinition = "text", nullable = false)
    var title: String,

    @Column(name = "culprit", columnDefinition = "text")
    var culprit: String?,

    @Column(name = "issue_type", length = 200)
    var issueType: String?,

    @Column(name = "level", nullable = false, length = 20)
    var level: String,

    @Column(name = "permalink", columnDefinition = "text")
    var permalink: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: SentryCandidateStatus,

    @Column(name = "case_id")
    var caseId: Long?,

    @Column(name = "occurrences", nullable = false)
    var occurrences: Int,

    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Instant,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,
)
