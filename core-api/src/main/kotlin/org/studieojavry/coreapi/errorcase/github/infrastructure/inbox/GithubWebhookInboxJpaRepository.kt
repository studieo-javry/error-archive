package org.studieojavry.coreapi.errorcase.github.infrastructure.inbox

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface GithubWebhookInboxJpaRepository : JpaRepository<GithubWebhookInboxEntity, String> {

    /** 처리 대상 id — 수신 순서대로. 단일 처리기(ShedLock)라 잠금 없이 id 만 뽑고, 건별 트랜잭션에서 다시 읽는다. */
    @Query(
        """
        SELECT e.deliveryId FROM GithubWebhookInboxEntity e
         WHERE e.status = :status AND e.nextRetryAt <= :now
         ORDER BY e.receivedAt
        """,
    )
    fun findDueIds(
        @Param("status") status: GithubWebhookInboxEntity.InboxStatus,
        @Param("now") now: Instant,
        pageable: org.springframework.data.domain.Pageable,
    ): List<String>

    /** 운영 도구 목록 — 최근 수신순. [status] 가 null 이면 전체. */
    @Query(
        """
        SELECT e FROM GithubWebhookInboxEntity e
         WHERE (:status IS NULL OR e.status = :status)
         ORDER BY e.receivedAt DESC
        """,
    )
    fun findRecent(@Param("status") status: GithubWebhookInboxEntity.InboxStatus?, limit: Limit): List<GithubWebhookInboxEntity>

    /** FAILED 전부를 처음 받은 상태로 되돌림 — 장애가 풀린 뒤 한 번에 다시 처리. */
    @Modifying
    @Query(
        """
        UPDATE GithubWebhookInboxEntity e
           SET e.status = org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxEntity.InboxStatus.PENDING,
               e.attempts = 0, e.lastError = NULL, e.processedAt = NULL, e.nextRetryAt = :now
         WHERE e.status = org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxEntity.InboxStatus.FAILED
        """,
    )
    fun requeueAllFailed(@Param("now") now: Instant): Int

    /** 운영 지표 — 상태별 건수. */
    fun countByStatus(status: GithubWebhookInboxEntity.InboxStatus): Long

    /** 운영 지표 — [status] 중 가장 먼저 받은 시각. 없으면 null. */
    @Query("SELECT MIN(e.receivedAt) FROM GithubWebhookInboxEntity e WHERE e.status = :status")
    fun oldestReceivedAt(@Param("status") status: GithubWebhookInboxEntity.InboxStatus): Instant?
}
