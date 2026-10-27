package org.studieojavry.coreapi.errorcase.github.infrastructure.inbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Duration
import java.time.Instant

/**
 * 웹훅 받은 편지함 row — `core_outbox_event` 와 같은 모양(밖으로 보낼 편지함 ↔ 받은 편지함).
 * 수신 즉시 PENDING 으로 INSERT, [GithubWebhookInboxProcessor] 가 처리 후 DONE/SKIPPED, 실패는 backoff 재시도 → FAILED.
 */
@Entity
@Table(
    name = "github_webhook_inbox",
    indexes = [Index(name = "ix_github_webhook_inbox_pending", columnList = "status, next_retry_at")],
)
class GithubWebhookInboxEntity(
    /** X-GitHub-Delivery — 재전송(Redeliver)도 같은 값이라 PK 로 멱등. */
    @Id
    @Column(name = "delivery_id", length = 64)
    var deliveryId: String,

    @Column(name = "event", nullable = false, length = 40)
    var event: String,

    @Column(name = "action", length = 40)
    var action: String?,

    @Column(name = "installation_id")
    var installationId: Long?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    var payload: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    var status: InboxStatus = InboxStatus.PENDING,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,

    @Column(name = "received_at", nullable = false)
    var receivedAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Column(name = "next_retry_at", nullable = false)
    var nextRetryAt: Instant = Instant.now(),
) {
    fun markProcessed(skipped: Boolean, at: Instant = Instant.now()) {
        status = if (skipped) InboxStatus.SKIPPED else InboxStatus.DONE
        processedAt = at
    }

    fun scheduleRetry(error: String, backoff: Duration) {
        attempts += 1
        lastError = error.take(2000)
        nextRetryAt = Instant.now().plus(backoff)
    }

    fun markFailed(error: String) {
        attempts += 1
        lastError = error.take(2000)
        status = InboxStatus.FAILED
    }

    enum class InboxStatus { PENDING, DONE, SKIPPED, FAILED }
}
