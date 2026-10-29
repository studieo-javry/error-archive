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
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubPrIssueRefEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubPrIssueRefKey
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubPullRequestEntity
import java.time.Instant

interface GithubIssueJpaRepository : JpaRepository<GithubIssueEntity, GithubItemKey>

interface GithubPullRequestJpaRepository : JpaRepository<GithubPullRequestEntity, GithubItemKey>

interface GithubPrIssueRefJpaRepository : JpaRepository<GithubPrIssueRefEntity, GithubPrIssueRefKey> {
    fun findAllByPrRepoIdAndPrNumber(prRepoId: Long, prNumber: Int): List<GithubPrIssueRefEntity>
}

interface CaseGithubLinkJpaRepository : JpaRepository<CaseGithubLinkEntity, Long> {
    fun findByCaseIdAndKindAndRepoIdAndNumber(caseId: Long, kind: String, repoId: Long, number: Int): CaseGithubLinkEntity?
    fun findAllByCaseIdOrderByLinkedAt(caseId: Long): List<CaseGithubLinkEntity>

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

    /** (occurredAt, id) 가 커서보다 오래된 것 — ix_case_github_event_cursor 를 역방향으로 탄다. */
    @Query(
        """
        SELECT e FROM CaseGithubEventEntity e
        WHERE e.caseId = :caseId AND (e.occurredAt < :at OR (e.occurredAt = :at AND e.id < :id))
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

    /** 같은 키면 최신 내용으로 덮어씀 — 커밋 단위 CI 집계용. */
    @Modifying
    @Query(
        value = """
        INSERT INTO case_github_event (case_id, type, kind, repo_id, number, occurred_at, data, dedupe_key, created_at)
        VALUES (:caseId, :type, :kind, :repoId, :number, :occurredAt, CAST(:data AS jsonb), :dedupeKey, :createdAt)
        ON CONFLICT (case_id, dedupe_key) DO UPDATE SET data = EXCLUDED.data, occurred_at = EXCLUDED.occurred_at
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("caseId") caseId: Long, @Param("type") type: String, @Param("kind") kind: String,
        @Param("repoId") repoId: Long, @Param("number") number: Int, @Param("occurredAt") occurredAt: Instant,
        @Param("data") data: String?, @Param("dedupeKey") dedupeKey: String, @Param("createdAt") createdAt: Instant,
    ): Int
}
