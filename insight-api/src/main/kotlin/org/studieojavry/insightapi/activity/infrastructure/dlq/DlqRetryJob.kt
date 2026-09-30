package org.studieojavry.insightapi.activity.infrastructure.dlq

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.beans.factory.annotation.Value
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
    // 운영 튜닝: 한 틱 처리량 / 폴링 주기. 복구 속도 ↔ DB·API 부하 트레이드오프(Phase 3 참고).
    @Value("\${insight.dlq.batch-size:50}") private val batchSize: Int,
) {
    private val log = KotlinLogging.logger {}

    private val tx: TransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    private val maxAttempts = 10
    private val baseBackoffMs = 5_000L
    private val maxBackoffMs = 3_600_000L  // 1h

    @Scheduled(fixedDelayString = "\${insight.dlq.interval-ms:30000}")
    @SchedulerLock(name = "insight-dlq-retry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    fun tick() {
        try {
            // 배치 id 만 짧은 tx 로 조회 → 각 행을 **독립 tx** 로 처리.
            // (동시 인스턴스는 @SchedulerLock 단일 리더로 차단되므로 FOR UPDATE 락 유지 불필요.)
            val ids = tx.execute { processFetch() } ?: return
            for (id in ids) processRow(id)
        } catch (ex: Exception) {
            log.error(ex) { "DLQ retry tick failed" }
        }
    }

    /** TransactionTemplate 안에서만 호출 — 재시도 대상 배치의 id 목록. */
    private fun processFetch(): List<Long> =
        dlqRepo.lockBatchForRetry(now = Instant.now(), limit = batchSize).mapNotNull { it.id }

    /**
     * 행 1건을 **독립 트랜잭션**으로 재처리.
     *
     * 배치 전체를 한 tx 로 돌리면 한 행의 ingest 실패가 `@Transactional`(REQUIRED) 조인으로
     * tx 를 rollback-only 로 만들어 **성공한 나머지 행까지 전부 롤백**되고, 실패 행의 attempts++ 도
     * 롤백돼 **영구 정체**하던 문제(경계검증 #2, 2026-09-23)를 수정한다.
     * → 성공 행은 각자 커밋, 실패 행만 자기 tx 로 backoff/DEAD 기록.
     */
    private fun processRow(id: Long) {
        val error: Exception? = try {
            tx.executeWithoutResult {
                val row = dlqRepo.findById(id).orElse(null) ?: return@executeWithoutResult
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
                log.info { "DLQ resolved: id=$id key=${row.idempotencyKey} after ${row.attempts} attempts" }
            }
            null
        } catch (ex: Exception) {
            ex
        }
        // ingest 실패로 위 tx 가 롤백되면(상태 미반영), 별도 tx 로 backoff/DEAD 기록.
        if (error != null) {
            try {
                tx.executeWithoutResult {
                    val row = dlqRepo.findById(id).orElse(null) ?: return@executeWithoutResult
                    handleRetryFailure(row, error)
                }
            } catch (ex: Exception) {
                log.error(ex) { "DLQ status update failed: id=$id" }
            }
        }
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
