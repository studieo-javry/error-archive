package org.studieojavry.publishapi.publishment.infrastructure.jpa

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 발행 멱등성 기록 정리 — 48시간 지난 row 삭제.
 *
 * 멱등 키는 클라이언트 재시도 창(수 분~수 시간) 동안만 유효하면 되므로 48h 면 충분.
 * 매일 03:10 KST 실행, shedlock 으로 멀티 노드에서 1회만.
 */
@Component
class PublishIdempotencyCleanupJob(
    private val jpa: PublishIdempotencyJpaRepository,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "pub-idempotency-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional
    fun purge() {
        val cutoff = Instant.now().minus(RETENTION)
        val deleted = jpa.deleteCreatedBefore(cutoff)
        if (deleted > 0) {
            log.info { "publish idempotency cleanup: deleted $deleted rows older than ${RETENTION.toHours()}h" }
        }
    }

    companion object {
        private val RETENTION: Duration = Duration.ofHours(48)
    }
}
