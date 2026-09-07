package org.studieojavry.publishapi.shared.infrastructure.outbox.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.publishapi.shared.infrastructure.outbox.OutboxEventEntity
import org.studieojavry.publishapi.shared.infrastructure.outbox.OutboxEventJpaRepository
import org.studieojavry.publishapi.shared.infrastructure.outbox.OutboxProperties
import org.studieojavry.publishapi.shared.infrastructure.outbox.OutboxStatus
import java.time.Duration
import java.time.Instant

@Service
class OutboxAdminUseCases(
    private val outboxRepo: OutboxEventJpaRepository,
    private val properties: OutboxProperties,
) {
    private val log = KotlinLogging.logger {}

    fun list(status: OutboxStatus?, limit: Int): List<OutboxEventEntity> =
        outboxRepo.findRecent(status?.name, limit.coerceIn(1, 500))

    @Transactional
    fun retry(id: Long): OutboxEventEntity {
        val row = outboxRepo.findById(id).orElseThrow {
            NoSuchElementException("outbox row not found: $id")
        }
        if (row.status == OutboxStatus.SENT) {
            throw IllegalStateException("cannot retry SENT row: id=$id")
        }
        row.status = OutboxStatus.PENDING
        row.attempts = 0
        row.lastError = null
        row.nextRetryAt = Instant.now()
        log.info { "publish outbox admin: retry id=$id" }
        return outboxRepo.save(row)
    }

    @Transactional
    fun delete(id: Long) {
        if (!outboxRepo.existsById(id)) {
            throw NoSuchElementException("outbox row not found: $id")
        }
        outboxRepo.deleteById(id)
        log.info { "publish outbox admin: delete id=$id" }
    }

    @Transactional
    fun cleanupSent(): Int {
        val cutoff = Instant.now().minus(Duration.ofDays(properties.sentTtlDays))
        val deleted = outboxRepo.deleteSentBefore(cutoff)
        log.info { "publish outbox admin: cleanup-sent purged $deleted rows" }
        return deleted
    }
}
