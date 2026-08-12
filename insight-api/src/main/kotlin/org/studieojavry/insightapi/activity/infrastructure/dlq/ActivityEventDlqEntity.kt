package org.studieojavry.insightapi.activity.infrastructure.dlq

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/**
 * Kafka consumer 가 처리 실패한 메시지의 격리실 (Dead Letter Queue).
 *
 * 두 종류 실패를 한 테이블에서 흡수:
 *  1. **Parse 실패** — JSON 깨짐 / 필수 필드 누락 / `Instant.parse` 실패 / unknown ActivityType 등 → status=DEAD 즉시
 *  2. **Ingest 실패** — DB lock / transient infra 실패 → status=PENDING, 별도 retry 잡이 재시도
 *
 * 운영 흐름:
 *  - Micrometer Gauge `insight.dlq.count` 가 > 0 → alert
 *  - admin endpoint (별도 phase) 로 row 조사 → 원인 분석 → `/retry` 또는 `delete`
 *
 * raw event 의 `idempotency_key` 와 *같은 의미* 의 키를 보존 — DLQ 에서 replay 시 dedup 작동.
 */
@Entity
@Table(
    name = "insight_activity_event_dlq",
    indexes = [
        Index(name = "ix_activity_dlq_status", columnList = "status, next_retry_at"),
        Index(name = "ix_activity_dlq_failed_at", columnList = "failed_at"),
    ],
)
class ActivityEventDlqEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 원본 Kafka payload (JSON 문자열 그대로). 디버깅 + replay 의 ground truth. */
    @Column(name = "raw_payload", columnDefinition = "text", nullable = false)
    var rawPayload: String,

    /** parse 성공 시 추출. parse 실패면 null. dedup / 조회 인덱싱 용. */
    @Column(name = "idempotency_key", length = 128)
    var idempotencyKey: String? = null,

    /** 실패 분류 (PARSE / INGEST). */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_kind", nullable = false, length = 16)
    var failureKind: DlqFailureKind,

    /** 사람이 읽을 수 있는 에러 메시지 (truncate 2000). */
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

    fun scheduleRetry(error: String, nextDelay: java.time.Duration) {
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

/** PARSE = 다시 시도해도 같은 결과. INGEST = transient 가능성, 자동 retry. */
enum class DlqFailureKind { PARSE, INGEST }

/** PENDING = retry 대기 / RESOLVED = 성공 / DEAD = 영구 실패 (수동 개입). */
enum class DlqStatus { PENDING, RESOLVED, DEAD }
