package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * SENT row TTL cleanup. 매일 03:00 KST 에 [OutboxProperties.sentTtlDays] 이상 지난 SENT row 삭제.
 *
 * DEAD row 는 자동 정리 X — 운영자가 admin endpoint 로 수동 처리 (감사 자료 유지).
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class OutboxCleanupJob(
    private val outboxRepo: OutboxEventJpaRepository,
    private val properties: OutboxProperties,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "core-outbox-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional
    fun purge() {
        val cutoff = Instant.now().minus(Duration.ofDays(properties.sentTtlDays))
        val deleted = outboxRepo.deleteSentBefore(cutoff)
        if (deleted > 0) {
            log.info { "outbox cleanup: deleted $deleted SENT rows older than ${properties.sentTtlDays}d" }
        }
    }
}
