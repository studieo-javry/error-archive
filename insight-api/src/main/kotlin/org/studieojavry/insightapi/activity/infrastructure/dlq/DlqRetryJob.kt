package org.studieojavry.insightapi.activity.infrastructure.dlq

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.insightapi.activity.application.usecase.IngestActivityEventUseCase
import org.studieojavry.insightapi.activity.infrastructure.kafka.ActivityEventConsumer
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant

/**
 * `insight_activity_event_dlq` 의 PENDING + INGEST row 를 주기적으로 재시도.
 *
 * 정책:
 *  - 30초 주기 polling — PARSE 는 자동 retry 안 함 (consumer 에서 즉시 DEAD).
 *  - 성공 시 row.markResolved (raw payload 보존 — 디버깅 / 감사).
 *  - 실패 시 attempts++, backoff (5s → 10s → 20s → ... → cap 1h), max 10 → DEAD.
 *  - retry 도 idempotent — ingestUseCase 의 idempotencyKey UNIQUE 로 중복 방지.
 *
 * **Transactional self-invocation 함정 회피** — `tx.execute { ... }` 로 명시 트랜잭션
 * (outbox relayer 와 동일 패턴, 2026-06-20 §4.5).
 */
@Component
class DlqRetryJob(
    private val dlqRepo: ActivityEventDlqRepository,
    private val ingestUseCase: IngestActivityEventUseCase,
    private val objectMapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}

    private val tx: TransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    private val batchSize = 50
    private val maxAttempts = 10
    private val baseBackoffMs = 5_000L
    private val maxBackoffMs = 3_600_000L  // 1h

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "insight-dlq-retry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    fun tick() {
        try {
            tx.execute { processBatch() }
        } catch (ex: Exception) {
            log.error(ex) { "DLQ retry tick failed" }
        }
    }

    /** TransactionTemplate 안에서만 호출. */
    private fun processBatch(): Int {
        val batch = dlqRepo.lockBatchForRetry(now = Instant.now(), limit = batchSize)
        if (batch.isEmpty()) return 0

        for (row in batch) {
            try {
                val msg = objectMapper.readValue(row.rawPayload, ActivityEventConsumer.ActivityMessage::class.java)
                val metaJson = msg.meta?.let { objectMapper.writeValueAsString(it) }
                ingestUseCase.invoke(IngestActivityEventUseCase.Input(
                    userId = msg.userId,
                    typeCode = msg.type,
                    occurredAt = Instant.parse(msg.occurredAt),
                    score = msg.score ?: 0,
                    idempotencyKey = msg.idempotencyKey,
                    metaJson = metaJson,
                ))
                row.markResolved()
                log.info { "DLQ resolved: id=${row.id} key=${row.idempotencyKey} after ${row.attempts} attempts" }
            } catch (ex: Exception) {
                handleRetryFailure(row, ex)
            }
        }
        return batch.size
    }

    private fun handleRetryFailure(row: ActivityEventDlqEntity, ex: Exception) {
        if (row.attempts + 1 >= maxAttempts) {
            row.markDead("max attempts exceeded; last: ${ex.message ?: ex.javaClass.simpleName}")
            log.warn { "DLQ row DEAD: id=${row.id} key=${row.idempotencyKey}" }
        } else {
            val backoffMs = computeBackoffMs(row.attempts + 1)
            row.scheduleRetry(ex.message ?: ex.javaClass.simpleName, Duration.ofMillis(backoffMs))
        }
    }

    private fun computeBackoffMs(attempts: Int): Long {
        val exp = (1L shl minOf(attempts, 20)) * baseBackoffMs
        return minOf(exp, maxBackoffMs)
    }
}
