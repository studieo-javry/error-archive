package org.studieojavry.notiapi.notification.infrastructure.dlq

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.notiapi.notification.infrastructure.kafka.KafkaEventType
import java.time.Duration
import java.time.Instant

/**
 * Kafka consumer 가 처리 실패한 알림 이벤트의 격리실 (Dead Letter Queue).
 *
 * 두 실패를 한 테이블에서 흡수:
 *  1. **PARSE 실패** — JSON 깨짐 / 필수 필드 누락 등 → status=DEAD 즉시(재시도해도 같은 결과).
 *  2. **INGEST 실패** — DB lock / iam 조회 순간 실패 등 transient → status=PENDING, [NotificationDlqRetryJob] 이 backoff 재시도.
 *
 * 재시도는 원본 [rawPayload] 를 [eventType] 에 맞는 use case 로 되돌려(KafkaEventProcessor.dispatch) 실행.
 * Receive*EventUseCase 는 실패 시 in-app write 트랜잭션이 롤백되므로(이메일은 swallow) **재실행이 멱등**하다.
 */
@Entity
@Table(
    name = "noti_notification_event_dlq",
    indexes = [
        Index(name = "ix_noti_dlq_status", columnList = "status, next_retry_at"),
        Index(name = "ix_noti_dlq_failed_at", columnList = "failed_at"),
    ],
)
class NotificationEventDlqEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 어느 consumer/use case 로 재처리할지 구분. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    var eventType: KafkaEventType,

    /** 원본 Kafka payload (JSON 문자열 그대로) — 재처리의 ground truth. */
    @Column(name = "raw_payload", columnDefinition = "text", nullable = false)
    var rawPayload: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_kind", nullable = false, length = 16)
    var failureKind: DlqFailureKind,

    @Column(name = "last_error", columnDefinition = "text", nullable = false)
    var lastError: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    var status: DlqStatus = DlqStatus.PENDING,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "failed_at", nullable = false)
    var failedAt: Instant = Instant.now(),

    @Column(name = "next_retry_at", nullable = false)
    var nextRetryAt: Instant = Instant.now(),

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
) {
    fun markResolved(at: Instant = Instant.now()) {
        status = DlqStatus.RESOLVED
        resolvedAt = at
    }

    fun scheduleRetry(error: String, nextDelay: Duration) {
        attempts += 1
        lastError = error.take(2000)
        nextRetryAt = Instant.now().plus(nextDelay)
    }

    fun markDead(error: String) {
        status = DlqStatus.DEAD
        attempts += 1
        lastError = error.take(2000)
    }
}

/** PARSE = 재시도해도 같은 결과(즉시 DEAD). INGEST = transient 가능성(자동 retry). */
enum class DlqFailureKind { PARSE, INGEST }

/** PENDING = retry 대기 / RESOLVED = 성공 / DEAD = 영구 실패(수동 개입). */
enum class DlqStatus { PENDING, RESOLVED, DEAD }
