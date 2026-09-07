package org.studieojavry.publishapi.shared.infrastructure.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface OutboxEventJpaRepository : JpaRepository<OutboxEventEntity, Long> {

    @Query(
        value = """
            SELECT * FROM pub_outbox_event
             WHERE status = 'PENDING' AND next_retry_at <= :now
             ORDER BY id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun lockBatchForRelay(@Param("now") now: Instant, @Param("limit") limit: Int): List<OutboxEventEntity>

    @Query(value = "SELECT COUNT(*) FROM pub_outbox_event WHERE status = 'PENDING'", nativeQuery = true)
    fun countPending(): Long

    @Query(value = "SELECT COUNT(*) FROM pub_outbox_event WHERE status = 'DEAD'", nativeQuery = true)
    fun countDead(): Long

    @Query(value = "SELECT MIN(created_at) FROM pub_outbox_event WHERE status = 'PENDING'", nativeQuery = true)
    fun oldestPendingCreatedAt(): Instant?

    @Modifying
    @Query(
        value = "DELETE FROM pub_outbox_event WHERE status = 'SENT' AND sent_at < :before",
        nativeQuery = true,
    )
    fun deleteSentBefore(@Param("before") before: Instant): Int

    /** Admin endpoint — status 필터 + 최신순 id DESC. status null = 전체. */
    @Query(
        value = """
            SELECT * FROM pub_outbox_event
             WHERE (:status IS NULL OR status = :status)
             ORDER BY id DESC
             LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecent(@Param("status") status: String?, @Param("limit") limit: Int): List<OutboxEventEntity>
}
