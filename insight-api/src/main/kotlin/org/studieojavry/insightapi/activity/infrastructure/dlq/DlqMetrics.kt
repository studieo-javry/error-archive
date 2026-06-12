package org.studieojavry.insightapi.activity.infrastructure.dlq

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * DLQ 운영 가시성 — Micrometer Gauge.
 *
 *  - `insight.dlq.pending.count` : 재시도 대기 중 row 수
 *  - `insight.dlq.dead.count`    : 영구 실패 (수동 개입 신호)
 *  - `insight.dlq.oldest_age`    : 가장 오래된 PENDING 의 age (seconds)
 *
 * **Alert 가이드**:
 *  - `dead.count > 0` → 즉시 운영자 개입
 *  - `oldest_age > 600` (10분) → broker 복구 + retry 로도 안 풀린 case → 확인
 */
@Component
class DlqMetrics(
    private val dlqRepo: ActivityEventDlqRepository,
    private val meterRegistry: MeterRegistry,
) {
    @PostConstruct
    fun registerGauges() {
        meterRegistry.gauge("insight.dlq.pending.count", this) { it.dlqRepo.countPending().toDouble() }
        meterRegistry.gauge("insight.dlq.dead.count", this) { it.dlqRepo.countDead().toDouble() }
        meterRegistry.gauge("insight.dlq.oldest_age", this) {
            val oldest = it.dlqRepo.oldestPendingFailedAt() ?: return@gauge 0.0
            Duration.between(oldest, Instant.now()).seconds.toDouble()
        }
    }
}
