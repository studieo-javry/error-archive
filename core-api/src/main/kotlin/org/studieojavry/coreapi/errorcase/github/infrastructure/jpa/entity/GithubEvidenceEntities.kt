package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant

/** (repo_id, number) 복합키 — Issue/PR 스냅샷 공용. */
data class GithubItemKey(var repoId: Long = 0, var number: Int = 0) : Serializable

@Entity
@Table(name = "github_issue")
@IdClass(GithubItemKey::class)
class GithubIssueEntity(
    @Id @Column(name = "repo_id") var repoId: Long,
    @Id @Column(name = "number") var number: Int,
    @Column(name = "title", nullable = false, length = 300) var title: String,
    @Column(name = "url", nullable = false, length = 500) var url: String,
    @Column(name = "state", nullable = false, length = 10) var state: String,
    @Column(name = "author_login", length = 100) var authorLogin: String?,
    @Column(name = "created_at_gh", nullable = false) var createdAtGh: Instant,
    @Column(name = "closed_at_gh") var closedAtGh: Instant?,
    @Column(name = "source_updated_at", nullable = false) var sourceUpdatedAt: Instant,
    @Column(name = "deleted", nullable = false) var deleted: Boolean,
)

@Entity
@Table(name = "github_pull_request")
@IdClass(GithubItemKey::class)
class GithubPullRequestEntity(
    @Id @Column(name = "repo_id") var repoId: Long,
    @Id @Column(name = "number") var number: Int,
    @Column(name = "title", nullable = false, length = 300) var title: String,
    @Column(name = "url", nullable = false, length = 500) var url: String,
    @Column(name = "state", nullable = false, length = 10) var state: String,
    @Column(name = "is_draft", nullable = false) var isDraft: Boolean,
    @Column(name = "author_login", length = 100) var authorLogin: String?,
    @Column(name = "head_sha", nullable = false, length = 40) var headSha: String,
    @Column(name = "base_ref", length = 200) var baseRef: String?,
    @Column(name = "additions") var additions: Int?,
    @Column(name = "deletions") var deletions: Int?,
    @Column(name = "changed_files") var changedFiles: Int?,
    @Column(name = "created_at_gh", nullable = false) var createdAtGh: Instant,
    @Column(name = "merged_at_gh") var mergedAtGh: Instant?,
    @Column(name = "closed_at_gh") var closedAtGh: Instant?,
    @Column(name = "source_updated_at", nullable = false) var sourceUpdatedAt: Instant,
    @Column(name = "ci_head_sha", length = 40) var ciHeadSha: String?,
    @Column(name = "ci_conclusion", length = 20) var ciConclusion: String?,
    @Column(name = "ci_total") var ciTotal: Int?,
    @Column(name = "ci_passed") var ciPassed: Int?,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ci_failed_names", columnDefinition = "jsonb") var ciFailedNames: String?,
    @Column(name = "ci_completed_at") var ciCompletedAt: Instant?,
)

data class GithubPrIssueRefKey(
    var prRepoId: Long = 0,
    var prNumber: Int = 0,
    var issueRepoId: Long = 0,
    var issueNumber: Int = 0,
) : Serializable

@Entity
@Table(name = "github_pr_issue_ref")
@IdClass(GithubPrIssueRefKey::class)
class GithubPrIssueRefEntity(
    @Id @Column(name = "pr_repo_id") var prRepoId: Long,
    @Id @Column(name = "pr_number") var prNumber: Int,
    @Id @Column(name = "issue_repo_id") var issueRepoId: Long,
    @Id @Column(name = "issue_number") var issueNumber: Int,
    @Column(name = "source", nullable = false, length = 10) var source: String,
    @Column(name = "observed_at", nullable = false) var observedAt: Instant,
)

@Entity
@Table(name = "case_github_link")
class CaseGithubLinkEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") var id: Long?,
    @Column(name = "case_id", nullable = false) var caseId: Long,
    @Column(name = "kind", nullable = false, length = 20) var kind: String,
    @Column(name = "repo_id", nullable = false) var repoId: Long,
    @Column(name = "number", nullable = false) var number: Int,
    @Column(name = "link_source", nullable = false, length = 20) var linkSource: String,
    @Column(name = "via_issue_repo_id") var viaIssueRepoId: Long?,
    @Column(name = "via_issue_number") var viaIssueNumber: Int?,
    @Column(name = "linked_by_user_id") var linkedByUserId: Long?,
    @Column(name = "linked_at", nullable = false) var linkedAt: Instant,
    @Column(name = "detached_at") var detachedAt: Instant?,
    @Column(name = "detached_by_user_id") var detachedByUserId: Long?,
)

data class CaseStepGithubLinkKey(var stepId: Long = 0, var linkId: Long = 0) : Serializable

@Entity
@Table(name = "case_step_github_link")
@IdClass(CaseStepGithubLinkKey::class)
class CaseStepGithubLinkEntity(
    @Id @Column(name = "step_id") var stepId: Long,
    @Id @Column(name = "link_id") var linkId: Long,
    @Column(name = "case_id", nullable = false) var caseId: Long,
    @Column(name = "source", nullable = false, length = 20) var source: String,
    @Column(name = "created_by_user_id") var createdByUserId: Long?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
)

@Entity
@Table(name = "case_github_event")
class CaseGithubEventEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") var id: Long?,
    @Column(name = "case_id", nullable = false) var caseId: Long,
    @Column(name = "type", nullable = false, length = 30) var type: String,
    @Column(name = "kind", nullable = false, length = 20) var kind: String,
    @Column(name = "repo_id", nullable = false) var repoId: Long,
    @Column(name = "number", nullable = false) var number: Int,
    @Column(name = "occurred_at", nullable = false) var occurredAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb") var data: String?,
    @Column(name = "dedupe_key", nullable = false, length = 160) var dedupeKey: String,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
)
