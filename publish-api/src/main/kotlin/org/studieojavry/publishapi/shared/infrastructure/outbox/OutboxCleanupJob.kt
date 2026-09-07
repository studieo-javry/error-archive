package org.studieojavry.publishapi.shared.infrastructure.outbox

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class OutboxCleanupJob(
    private val outboxRepo: OutboxEventJpaRepository,
    private val properties: OutboxProperties,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "pub-outbox-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional
    fun purge() {
        val cutoff = Instant.now().minus(Duration.ofDays(properties.sentTtlDays))
        val deleted = outboxRepo.deleteSentBefore(cutoff)
        if (deleted > 0) {
            log.info { "publish outbox cleanup: deleted $deleted SENT rows older than ${properties.sentTtlDays}d" }
        }
    }
}
