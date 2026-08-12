package org.studieojavry.iamapi.shared.infrastructure.outbox

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Outbox / Relayer 동작 파라미터. 자세한 설명은 docs/outbox-design.md 참조.
 */
@ConfigurationProperties(prefix = "iam.outbox")
data class OutboxProperties(
    val busyDelayMs: Long = 200,
    val idleInitialDelayMs: Long = 1000,
    val idleMaxDelayMs: Long = 5000,
    val idleMultiplier: Double = 2.0,
    val batchSize: Int = 50,
    val maxAttempts: Int = 10,
    val baseBackoffMs: Long = 5000,
    val maxBackoffMs: Long = 3600000,
    val sentTtlDays: Long = 30,
)
