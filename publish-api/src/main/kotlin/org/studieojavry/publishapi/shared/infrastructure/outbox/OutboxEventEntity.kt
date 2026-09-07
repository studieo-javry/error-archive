package org.studieojavry.publishapi.shared.infrastructure.outbox

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

@Entity
@Table(
    name = "pub_outbox_event",
    indexes = [
        Index(name = "ix_pub_outbox_pending", columnList = "status, next_retry_at"),
        Index(name = "ix_pub_outbox_sent_for_purge", columnList = "sent_at"),
    ],
)
class OutboxEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "aggregate_type", nullable = false, length = 40)
    var aggregateType: String,

    @Column(name = "aggregate_id", nullable = false, length = 80)
    var aggregateId: String,

    @Column(name = "topic", nullable = false, length = 120)
    var topic: String,

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

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

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
