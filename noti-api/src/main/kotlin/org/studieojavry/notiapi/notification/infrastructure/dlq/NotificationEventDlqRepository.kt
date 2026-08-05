package org.studieojavry.notiapi.notification.infrastructure.dlq

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface NotificationEventDlqRepository : JpaRepository<NotificationEventDlqEntity, Long> {

    /**
     * Retry 잡 polling — PENDING + INGEST + 시간 도래분.
     * `FOR UPDATE SKIP LOCKED` 로 멀티 인스턴스에서도 서로 다른 row 만 집어 중복 처리 방지(shedlock 불요).
     * PARSE 는 자동 retry 안 함(consumer 에서 즉시 DEAD).
     */
    @Query(
        value = """
            SELECT * FROM noti_notification_event_dlq
             WHERE status = 'PENDING'
               AND failure_kind = 'INGEST'
               AND next_retry_at <= :now
             ORDER BY id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun lockBatchForRetry(@Param("now") now: Instant, @Param("limit") limit: Int): List<NotificationEventDlqEntity>

    @Query(value = "SELECT COUNT(*) FROM noti_notification_event_dlq WHERE status = 'PENDING'", nativeQuery = true)
    fun countPending(): Long

    @Query(value = "SELECT COUNT(*) FROM noti_notification_event_dlq WHERE status = 'DEAD'", nativeQuery = true)
    fun countDead(): Long

    @Query(value = "SELECT MIN(failed_at) FROM noti_notification_event_dlq WHERE status = 'PENDING'", nativeQuery = true)
    fun oldestPendingFailedAt(): Instant?

    /** Admin endpoint — status / kind 필터 + 최신순. 둘 다 null = 전체. */
    @Query(
        value = """
            SELECT * FROM noti_notification_event_dlq
             WHERE (:status IS NULL OR status = :status)
               AND (:kind   IS NULL OR failure_kind = :kind)
             ORDER BY id DESC
             LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecent(
        @Param("status") status: String?,
        @Param("kind") kind: String?,
        @Param("limit") limit: Int,
    ): List<NotificationEventDlqEntity>
}
