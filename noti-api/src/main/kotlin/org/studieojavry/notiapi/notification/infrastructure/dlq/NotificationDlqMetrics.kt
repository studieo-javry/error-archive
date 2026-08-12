package org.studieojavry.notiapi.notification.infrastructure.dlq

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * DLQ 운영 가시성 — Micrometer Gauge (prometheus 로 노출).
 *
 *  - `noti.dlq.pending.count` : 재시도 대기 row 수
 *  - `noti.dlq.dead.count`    : 영구 실패 (수동 개입 신호)
 *  - `noti.dlq.oldest_age`    : 가장 오래된 PENDING 의 age(seconds)
 *
 * **Alert 가이드**: `dead.count > 0` → 운영자 개입 / `oldest_age > 600`(10분) → 재시도로도 안 풀린 case 확인.
 */
@Component
class NotificationDlqMetrics(
    private val dlqRepo: NotificationEventDlqRepository,
    private val meterRegistry: MeterRegistry,
) {
    @PostConstruct
    fun registerGauges() {
        meterRegistry.gauge("noti.dlq.pending.count", this) { it.dlqRepo.countPending().toDouble() }
        meterRegistry.gauge("noti.dlq.dead.count", this) { it.dlqRepo.countDead().toDouble() }
        meterRegistry.gauge("noti.dlq.oldest_age", this) {
            val oldest = it.dlqRepo.oldestPendingFailedAt() ?: return@gauge 0.0
            Duration.between(oldest, Instant.now()).seconds.toDouble()
        }
    }
}
