package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.CaseGithubEventEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.CaseGithubLinkEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.CaseStepGithubLinkEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.CaseStepGithubLinkKey
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubIssueEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubItemKey
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubItemReconcileEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubItemReconcileKey
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubPrIssueRefEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubPrIssueRefKey
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubPullRequestEntity
import java.time.Instant

interface GithubIssueJpaRepository : JpaRepository<GithubIssueEntity, GithubItemKey>

interface GithubPullRequestJpaRepository : JpaRepository<GithubPullRequestEntity, GithubItemKey> {
    /** 이 커밋이 head 또는 머지 커밋인 PR — ix_github_pr_head / ix_github_pr_merge_commit. */
    @Query("SELECT p FROM GithubPullRequestEntity p WHERE p.repoId = :repoId AND (p.headSha = :sha OR p.mergeCommitSha = :sha)")
    fun findAllByCommit(@Param("repoId") repoId: Long, @Param("sha") sha: String): List<GithubPullRequestEntity>
}

interface GithubPrIssueRefJpaRepository : JpaRepository<GithubPrIssueRefEntity, GithubPrIssueRefKey> {
    fun findAllByPrRepoIdAndPrNumber(prRepoId: Long, prNumber: Int): List<GithubPrIssueRefEntity>
    fun findAllByPrRepoIdInAndPrNumberIn(prRepoIds: Collection<Long>, prNumbers: Collection<Int>): List<GithubPrIssueRefEntity>
    fun findAllByIssueRepoIdAndIssueNumber(issueRepoId: Long, issueNumber: Int): List<GithubPrIssueRefEntity>
}

interface CaseGithubLinkJpaRepository : JpaRepository<CaseGithubLinkEntity, Long> {
    fun findByCaseIdAndKindAndRepoIdAndNumber(caseId: Long, kind: String, repoId: Long, number: Int): CaseGithubLinkEntity?
    fun findAllByCaseIdOrderByLinkedAt(caseId: Long): List<CaseGithubLinkEntity>
    fun findAllByKindAndRepoIdAndNumberAndDetachedAtIsNull(kind: String, repoId: Long, number: Int): List<CaseGithubLinkEntity>
    fun existsByKindAndRepoIdAndNumber(kind: String, repoId: Long, number: Int): Boolean
    fun existsByKindAndRepoIdAndDetachedAtIsNull(kind: String, repoId: Long): Boolean

    @Modifying
    @Query("DELETE FROM CaseGithubLinkEntity l WHERE l.caseId = :caseId")
    fun deleteAllByCaseId(@Param("caseId") caseId: Long): Int
}

interface CaseStepGithubLinkJpaRepository : JpaRepository<CaseStepGithubLinkEntity, CaseStepGithubLinkKey> {
    fun findAllByCaseIdOrderByCreatedAt(caseId: Long): List<CaseStepGithubLinkEntity>
    fun findAllByStepId(stepId: Long): List<CaseStepGithubLinkEntity>

    @Modifying
    @Query("DELETE FROM CaseStepGithubLinkEntity s WHERE s.linkId = :linkId")
    fun deleteAllByLinkId(@Param("linkId") linkId: Long): Int

    @Modifying
    @Query("DELETE FROM CaseStepGithubLinkEntity s WHERE s.stepId = :stepId")
    fun deleteAllByStepId(@Param("stepId") stepId: Long): Int

    @Modifying
    @Query("DELETE FROM CaseStepGithubLinkEntity s WHERE s.caseId = :caseId")
    fun deleteAllByCaseId(@Param("caseId") caseId: Long): Int
}

interface CaseGithubEventJpaRepository : JpaRepository<CaseGithubEventEntity, Long> {
    fun findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId: Long): List<CaseGithubEventEntity>
    fun findAllByCaseIdOrderByOccurredAtDescIdDesc(caseId: Long, limit: Limit): List<CaseGithubEventEntity>
    fun countByCaseId(caseId: Long): Long

    /**
     * (occurredAt, id) 가 커서보다 오래된 것 — ix_case_github_event_cursor 를 역방향으로 탄다.
     * `occurredAt <= :at` 은 결과를 바꾸지 않는 중복 조건이지만 없으면 OR 때문에 인덱스 시작 위치를 못 잡고
     * Case 의 이벤트를 최신부터 훑으며 거른다(1만 건 중간 쪽에서 5,001행 버림). 있으면 커서 위치부터 LIMIT 만큼만 읽는다.
     */
    @Query(
        """
        SELECT e FROM CaseGithubEventEntity e
        WHERE e.caseId = :caseId AND e.occurredAt <= :at AND (e.occurredAt < :at OR e.id < :id)
        ORDER BY e.occurredAt DESC, e.id DESC
        """,
    )
    fun findOlderThan(@Param("caseId") caseId: Long, @Param("at") at: Instant, @Param("id") id: Long, limit: Limit): List<CaseGithubEventEntity>

    @Modifying
    @Query("DELETE FROM CaseGithubEventEntity e WHERE e.caseId = :caseId")
    fun deleteAllByCaseId(@Param("caseId") caseId: Long): Int

    /** (case_id, dedupe_key) 충돌 시 무시 — 동시 기록에도 유니크 위반 없이 한 줄만. */
    @Modifying
    @Query(
        value = """
        INSERT INTO case_github_event (case_id, type, kind, repo_id, number, occurred_at, data, dedupe_key, created_at)
        VALUES (:caseId, :type, :kind, :repoId, :number, :occurredAt, CAST(:data AS jsonb), :dedupeKey, :createdAt)
        ON CONFLICT (case_id, dedupe_key) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("caseId") caseId: Long, @Param("type") type: String, @Param("kind") kind: String,
        @Param("repoId") repoId: Long, @Param("number") number: Int, @Param("occurredAt") occurredAt: Instant,
        @Param("data") data: String?, @Param("dedupeKey") dedupeKey: String, @Param("createdAt") createdAt: Instant,
    ): Int

    /** 같은 키면 최신 내용으로 덮어씀 — 커밋 단위 CI 집계용. 내용이 같으면 손대지 않음(0 — 리비전도 그대로). */
    @Modifying
    @Query(
        value = """
        INSERT INTO case_github_event (case_id, type, kind, repo_id, number, occurred_at, data, dedupe_key, created_at)
        VALUES (:caseId, :type, :kind, :repoId, :number, :occurredAt, CAST(:data AS jsonb), :dedupeKey, :createdAt)
        ON CONFLICT (case_id, dedupe_key) DO UPDATE SET data = EXCLUDED.data, occurred_at = EXCLUDED.occurred_at
         WHERE case_github_event.data IS DISTINCT FROM EXCLUDED.data
            OR case_github_event.occurred_at IS DISTINCT FROM EXCLUDED.occurred_at
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("caseId") caseId: Long, @Param("type") type: String, @Param("kind") kind: String,
        @Param("repoId") repoId: Long, @Param("number") number: Int, @Param("occurredAt") occurredAt: Instant,
        @Param("data") data: String?, @Param("dedupeKey") dedupeKey: String, @Param("createdAt") createdAt: Instant,
    ): Int

    // ── 활동 리비전(case_github_activity_revision — 값은 이 저장소의 네이티브 쿼리로만 다룸) ──
    @Modifying
    @Query(
        value = """
        INSERT INTO case_github_activity_revision (case_id, revision, updated_at) VALUES (:caseId, 1, :now)
        ON CONFLICT (case_id) DO UPDATE SET revision = case_github_activity_revision.revision + 1, updated_at = EXCLUDED.updated_at
        """,
        nativeQuery = true,
    )
    fun bumpRevision(@Param("caseId") caseId: Long, @Param("now") now: Instant): Int

    @Query(value = "SELECT revision FROM case_github_activity_revision WHERE case_id = :caseId", nativeQuery = true)
    fun findRevision(@Param("caseId") caseId: Long): Long?

    @Modifying
    @Query(value = "DELETE FROM case_github_activity_revision WHERE case_id = :caseId", nativeQuery = true)
    fun deleteRevision(@Param("caseId") caseId: Long): Int
}

interface GithubItemReconcileJpaRepository : JpaRepository<GithubItemReconcileEntity, GithubItemReconcileKey> {
    /**
     * 재동기화 대상 (kind, repo_id, number) — 떼지 않은 연결이 있고, 끝나지 않았거나(열림·CI 진행 중) [recentSince] 이후 바뀐 Issue/PR 중
     * [checkedBefore] 이후 확인하지 않은 것. 한 번도 확인 안 한 것 → 오래전에 확인한 것 순.
     * 지워진 Issue 와 스냅샷이 없는 대상은 고르지 않는다(다시 조회할 근거가 없음).
     */
    @Query(
        value = """
        SELECT t.kind, t.repo_id, t.number
          FROM (SELECT DISTINCT l.kind, l.repo_id, l.number FROM case_github_link l WHERE l.detached_at IS NULL) t
          LEFT JOIN github_issue i ON t.kind = 'ISSUE' AND i.repo_id = t.repo_id AND i.number = t.number
          LEFT JOIN github_pull_request p ON t.kind = 'PULL_REQUEST' AND p.repo_id = t.repo_id AND p.number = t.number
          LEFT JOIN github_item_reconcile r ON r.kind = t.kind AND r.repo_id = t.repo_id AND r.number = t.number
         WHERE (r.checked_at IS NULL OR r.checked_at < :checkedBefore)
           AND ((t.kind = 'ISSUE' AND i.deleted = FALSE AND (i.state = 'OPEN' OR i.source_updated_at >= :recentSince))
             OR (t.kind = 'PULL_REQUEST' AND (p.state = 'OPEN' OR p.source_updated_at >= :recentSince OR p.ci_conclusion = 'PENDING')))
         ORDER BY r.checked_at NULLS FIRST, t.repo_id, t.number
         LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findDue(
        @Param("checkedBefore") checkedBefore: Instant,
        @Param("recentSince") recentSince: Instant,
        @Param("limit") limit: Int,
    ): List<Array<Any>>

    @Modifying
    @Query(
        value = """
        INSERT INTO github_item_reconcile (kind, repo_id, number, checked_at) VALUES (:kind, :repoId, :number, :at)
        ON CONFLICT (kind, repo_id, number) DO UPDATE SET checked_at = EXCLUDED.checked_at
        """,
        nativeQuery = true,
    )
    fun markChecked(@Param("kind") kind: String, @Param("repoId") repoId: Long, @Param("number") number: Int, @Param("at") at: Instant): Int
}
