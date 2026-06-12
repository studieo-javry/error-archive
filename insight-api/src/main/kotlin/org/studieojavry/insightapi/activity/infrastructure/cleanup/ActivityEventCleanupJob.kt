package org.studieojavry.insightapi.activity.infrastructure.cleanup

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.insightapi.activity.config.RetentionProperties
import org.studieojavry.insightapi.activity.infrastructure.ActivityEventJpaRepository
import java.time.Duration
import java.time.Instant

/**
 * `insight_activity_event` 의 raw 데이터를 [RetentionProperties.eventTtlDays] 기준 batch 삭제.
 *
 * 정책:
 *  - 잔디는 12개월 노출 + 월말 정책 변경 / rebuild 여유 1개월 = **13개월 보존**.
 *  - daily 집계 (`insight_activity_daily`) 는 *영구 보존* (column 수 적고 backfill 용도).
 *  - batch 단위 DELETE — 한 트랜잭션에 너무 많이 잡으면 lock 길어짐. 0 반환까지 반복.
 *
 * 매일 03:00 KST 실행. ShedLock 으로 다중 인스턴스 중 leader 만.
 *
 * **Transactional self-invocation 함정 회피** — `tx.execute { ... }` 로 명시 트랜잭션. core/iam/publish-api
 * 의 `OutboxRelayer` 와 동일 패턴 (2026-06-20 §4.5 참조).
 */
@Component
class ActivityEventCleanupJob(
    private val eventRepo: ActivityEventJpaRepository,
    private val properties: RetentionProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}

    private val tx: TransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "insight-event-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    fun purge() {
        val cutoff = Instant.now().minus(Duration.ofDays(properties.eventTtlDays))
        var totalDeleted = 0
        var rounds = 0
        // batch 가 비어 끝날 때까지 반복 — 매 round 별도 트랜잭션
        while (true) {
            // tx.execute<Int> — block 이 Int 를 반환하므로 결과는 platform-type Int (null 아님)
            val deleted: Int = tx.execute { eventRepo.deleteOlderThan(cutoff, properties.deleteBatchSize) }
            if (deleted == 0) break
            totalDeleted += deleted
            rounds += 1
            // 안전망 — 비정상적으로 많은 round 면 abort (다음 cron 에서 이어서)
            if (rounds >= 200) {
                log.warn { "activity cleanup capped at $rounds rounds, deleted=$totalDeleted — resume next run" }
                break
            }
        }
        if (totalDeleted > 0) {
            log.info { "activity cleanup: deleted $totalDeleted raw events older than ${properties.eventTtlDays}d ($rounds rounds)" }
        }
    }
}
