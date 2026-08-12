package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface OutboxEventJpaRepository : JpaRepository<OutboxEventEntity, Long> {

    /**
     * 폴링 핵심 쿼리.
     *
     * `FOR UPDATE SKIP LOCKED` — 동일 row 를 다른 worker 가 잠갔으면 skip + 다음 row.
     * ShedLock 으로 단일 leader 강제하지만 안전망 + 향후 scale-out 여지.
     *
     * `ORDER BY id` — INSERT 순서 보존 (consumer 가 commutative 라 무관하나 디버깅 일관성).
     */
    @Query(
        value = """
            SELECT * FROM core_outbox_event
             WHERE status = 'PENDING' AND next_retry_at <= :now
             ORDER BY id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun lockBatchForRelay(@Param("now") now: Instant, @Param("limit") limit: Int): List<OutboxEventEntity>

    @Query(value = "SELECT COUNT(*) FROM core_outbox_event WHERE status = 'PENDING'", nativeQuery = true)
    fun countPending(): Long

    @Query(value = "SELECT COUNT(*) FROM core_outbox_event WHERE status = 'DEAD'", nativeQuery = true)
    fun countDead(): Long

    /** Micrometer Gauge 용 — 가장 오래된 pending row 의 created_at. 없으면 null. */
    @Query(value = "SELECT MIN(created_at) FROM core_outbox_event WHERE status = 'PENDING'", nativeQuery = true)
    fun oldestPendingCreatedAt(): Instant?

    /** SENT TTL cleanup. */
    @Modifying
    @Query(
        value = "DELETE FROM core_outbox_event WHERE status = 'SENT' AND sent_at < :before",
        nativeQuery = true,
    )
    fun deleteSentBefore(@Param("before") before: Instant): Int

    /**
     * Admin endpoint — status 필터 + 최신순. status null = 전체.
     * created_at DESC 로 *최근 row 가 먼저* 보이도록 (DEAD 조사 시 가장 최근 실패가 위로).
     */
    @Query(
        value = """
            SELECT * FROM core_outbox_event
             WHERE (:status IS NULL OR status = :status)
             ORDER BY id DESC
             LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecent(@Param("status") status: String?, @Param("limit") limit: Int): List<OutboxEventEntity>
}
