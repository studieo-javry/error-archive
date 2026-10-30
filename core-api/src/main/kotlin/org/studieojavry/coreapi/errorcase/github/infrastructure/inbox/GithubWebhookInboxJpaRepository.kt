package org.studieojavry.coreapi.errorcase.github.infrastructure.inbox

import org.springframework.data.jpa.repository.JpaRepository
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
}
