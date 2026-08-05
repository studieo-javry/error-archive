package org.studieojavry.notiapi.notification.infrastructure.dlq

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.notiapi.notification.infrastructure.kafka.KafkaEventProcessor
import java.time.Duration
import java.time.Instant

/**
 * `noti_notification_event_dlq` 의 PENDING + INGEST row 를 주기적으로 자동 재처리.
 *
 * 정책:
 *  - 30초 주기 polling. PARSE 는 자동 retry 안 함(consumer 에서 즉시 DEAD).
 *  - 재처리 = [KafkaEventProcessor.dispatch] 로 원래 use case 재실행(Kafka 안 거침).
 *  - 성공 → markResolved(raw payload 보존 — 감사/디버깅). 실패 → attempts++, backoff(5s→10s→…→cap 1h),
 *    max 10 회 초과 → DEAD(수동 개입).
 *  - `FOR UPDATE SKIP LOCKED` batch 로 멀티 인스턴스에서도 중복 없이 분산 처리(shedlock 불요).
 *  - Receive*EventUseCase 는 실패 시 in-app write 가 롤백되므로 **재실행 멱등** — 중복 알림 없음.
 *
 * self-invocation 트랜잭션 함정 회피 위해 TransactionTemplate(REQUIRES_NEW) 사용.
 */
@Component
class NotificationDlqRetryJob(
    private val dlqRepo: NotificationEventDlqRepository,
    private val processor: KafkaEventProcessor,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}

    private val tx = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    private val batchSize = 50
    private val maxAttempts = 10
    private val baseBackoffMs = 5_000L
    private val maxBackoffMs = 3_600_000L // 1h

    @Scheduled(fixedDelay = 30_000)
    fun tick() {
        try {
            tx.execute { processBatch() }
        } catch (ex: Exception) {
            log.error(ex) { "[noti-dlq] retry tick failed" }
        }
    }

    private fun processBatch(): Int {
        val batch = dlqRepo.lockBatchForRetry(now = Instant.now(), limit = batchSize)
        for (row in batch) {
            try {
                processor.dispatch(row.eventType, row.rawPayload)
                row.markResolved()
                log.info { "[noti-dlq] resolved id=${row.id} type=${row.eventType} after ${row.attempts} attempts" }
            } catch (ex: Exception) {
                handleRetryFailure(row, ex)
            }
        }
        return batch.size
    }

    private fun handleRetryFailure(row: NotificationEventDlqEntity, ex: Exception) {
        if (row.attempts + 1 >= maxAttempts) {
            row.markDead("max attempts exceeded; last: ${ex.message ?: ex.javaClass.simpleName}")
            log.warn(ex) { "[noti-dlq] DEAD id=${row.id} type=${row.eventType}" }
        } else {
            row.scheduleRetry(ex.message ?: ex.javaClass.simpleName, Duration.ofMillis(computeBackoffMs(row.attempts + 1)))
        }
    }

    private fun computeBackoffMs(attempts: Int): Long {
        val exp = (1L shl minOf(attempts, 20)) * baseBackoffMs
        return minOf(exp, maxBackoffMs)
    }
}
