package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Outbox 운영 가시성 — Micrometer Gauge 로 노출. Prometheus / Actuator 통해 수집.
 *
 *  - `outbox.pending.count`        : 현재 PENDING row 수
 *  - `outbox.dead.count`           : 누적 DEAD row 수 (운영자 개입 필요 신호)
 *  - `outbox.pending.oldest_age`   : 가장 오래된 PENDING row 의 age (seconds). 0 = pending 없음
 *
 * **Alert 가이드**:
 *  - `outbox.pending.oldest_age > 300` (5분) → broker 장애 의심
 *  - `outbox.dead.count > 0` → 운영자 즉시 확인 (admin endpoint 로 재시도/삭제)
 *
 * Gauge 가 매 scrape 마다 DB 조회 — Prometheus scrape interval (보통 15-60s) 와 맞물려 부담 최소.
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class OutboxMetrics(
    private val outboxRepo: OutboxEventJpaRepository,
    private val meterRegistry: MeterRegistry,
) {
    @PostConstruct
    fun registerGauges() {
        meterRegistry.gauge("outbox.pending.count", this) { it.outboxRepo.countPending().toDouble() }
        meterRegistry.gauge("outbox.dead.count", this) { it.outboxRepo.countDead().toDouble() }
        meterRegistry.gauge("outbox.pending.oldest_age", this) {
            val oldest = it.outboxRepo.oldestPendingCreatedAt() ?: return@gauge 0.0
            Duration.between(oldest, Instant.now()).seconds.toDouble()
        }
    }
}
