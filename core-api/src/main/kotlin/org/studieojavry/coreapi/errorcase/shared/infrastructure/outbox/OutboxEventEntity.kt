package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * Outbox row — 도메인 트랜잭션에서 INSERT, Relayer 가 SELECT → Kafka send 후 mark SENT.
 *
 * `ddl-auto: update` 가 본 Entity 로부터 `core_outbox_event` 테이블 + 인덱스 생성.
 * 운영(prod/stg) 은 Flyway 마이그레이션으로 별도 관리 필요.
 */
@Entity
@Table(
    name = "core_outbox_event",
    indexes = [
        // 폴링 쿼리 (WHERE status='PENDING' AND next_retry_at <= now ORDER BY id) 가속.
        // status 컬럼은 카디널리티 낮아 단독 인덱스는 비효율. 복합 인덱스 + status 절은
        // partial index 가 PostgreSQL 에서 가장 효율적이나 JPA 표준엔 partial 표현이 없어
        // 본 인덱스로 충분히 cover. 운영에서 필요 시 native DDL 로 partial 추가.
        Index(name = "ix_core_outbox_pending", columnList = "status, next_retry_at"),
        Index(name = "ix_core_outbox_sent_for_purge", columnList = "sent_at"),
    ],
)
class OutboxEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 'CASE', 'STEP', 'COMMENT', 'MENTION' 등 — 디버깅·필터링용. */
    @Column(name = "aggregate_type", nullable = false, length = 40)
    var aggregateType: String,

    /** 도메인 식별 (예: "case:42", "mention:comment-7:user-101"). idempotencyKey 와 동일 사용 가능. */
    @Column(name = "aggregate_id", nullable = false, length = 80)
    var aggregateId: String,

    @Column(name = "topic", nullable = false, length = 120)
    var topic: String,

    /** Kafka partition key. null = round-robin. */
    @Column(name = "kafka_key", length = 120)
    var kafkaKey: String?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    var payload: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    /** SENT 일 때만 의미. */
    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    /** 다음 재시도 가능 시각. INSERT 시 = createdAt. backoff 시 미래로. */
    @Column(name = "next_retry_at", nullable = false)
    var nextRetryAt: Instant = Instant.now(),
) {
    fun markSent(at: Instant = Instant.now()) {
        status = OutboxStatus.SENT
        sentAt = at
        lastError = null
    }

    fun scheduleRetry(error: String, nextDelay: java.time.Duration) {
        attempts += 1
        lastError = error.take(2000)
        nextRetryAt = Instant.now().plus(nextDelay)
    }

    fun markDead(error: String) {
        status = OutboxStatus.DEAD
        attempts += 1
        lastError = error.take(2000)
    }
}

enum class OutboxStatus { PENDING, SENT, DEAD }
