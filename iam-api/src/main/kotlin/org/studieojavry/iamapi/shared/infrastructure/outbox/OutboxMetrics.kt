package org.studieojavry.iamapi.shared.infrastructure.outbox

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

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
