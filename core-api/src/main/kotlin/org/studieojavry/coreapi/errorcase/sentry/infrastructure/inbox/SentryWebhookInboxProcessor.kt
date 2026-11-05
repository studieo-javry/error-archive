package org.studieojavry.coreapi.errorcase.sentry.infrastructure.inbox

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.sentry.application.usecase.ProcessSentryWebhookEventUseCase
import org.studieojavry.coreapi.errorcase.sentry.config.ConditionalOnSentryEnabled
import org.studieojavry.coreapi.errorcase.sentry.config.SentryProperties
import java.time.Duration
import java.time.Instant

/**
 * 받은 편지함 처리기 — 주기적으로 PENDING 을 꺼내 [ProcessSentryWebhookEventUseCase] 로 처리.
 *
 * - `@SchedulerLock`: 다중 인스턴스 중 하나만 실행.
 * - **건별 트랜잭션**: 처리 + DONE 표시가 한 트랜잭션. 한 건 실패가 배치 전체를 깨지 않는다.
 * - 실패: 별도 트랜잭션에서 attempts++ · backoff(2^n·base, 상한) · 최대 횟수 넘으면 FAILED.
 * - github 처리기와 달리 외부 API 호출이 없어 rate-limit 유예 로직은 없다.
 */
@Component
@ConditionalOnSentryEnabled
class SentryWebhookInboxProcessor(
    private val inboxRepo: SentryWebhookInboxJpaRepository,
    private val processEvent: ProcessSentryWebhookEventUseCase,
    private val properties: SentryProperties,
    private val meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelayString = "\${core.sentry.inbox.poll-delay-ms:2000}")
    @SchedulerLock(name = "sentry-webhook-inbox", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    fun tick() {
        val ids = inboxRepo.findDueIds(
            SentryWebhookInboxEntity.InboxStatus.PENDING,
            Instant.now(),
            PageRequest.of(0, properties.inbox.batchSize),
        )
        ids.forEach(::processOne)
    }

    fun processOne(eventKey: String) {
        try {
            tx.executeWithoutResult {
                val row = inboxRepo.findById(eventKey).orElse(null)
                    ?.takeIf { it.status == SentryWebhookInboxEntity.InboxStatus.PENDING }
                    ?: return@executeWithoutResult
                val result = processEvent.invoke(row.resource, row.action, row.payload)
                val skipped = result == ProcessSentryWebhookEventUseCase.Result.SKIPPED
                row.markProcessed(skipped = skipped)
                count(row.resource, if (skipped) OUTCOME_SKIPPED else OUTCOME_DONE)
            }
        } catch (ex: Exception) {
            recordFailure(eventKey, ex)
        }
    }

    private fun recordFailure(eventKey: String, ex: Exception) {
        val reason = ex.message ?: ex.javaClass.simpleName
        tx.executeWithoutResult {
            val row = inboxRepo.findById(eventKey).orElse(null) ?: return@executeWithoutResult
            if (row.attempts + 1 >= properties.inbox.maxAttempts) {
                row.markFailed(reason)
                count(row.resource, OUTCOME_FAILED)
                log.warn(ex) { "sentry webhook FAILED: key=$eventKey resource=${row.resource} attempts=${row.attempts}" }
            } else {
                val exp = (1L shl minOf(row.attempts + 1, 20)) * properties.inbox.baseBackoffMs
                row.scheduleRetry(reason, Duration.ofMillis(minOf(exp, properties.inbox.maxBackoffMs)))
                count(row.resource, OUTCOME_RETRY)
                log.debug { "sentry webhook retry: key=$eventKey attempt=${row.attempts}" }
            }
        }
    }

    private fun count(resource: String, outcome: String) {
        meterRegistry.counter(METRIC_PROCESSED, "resource", resource, "outcome", outcome).increment()
    }

    companion object {
        const val METRIC_PROCESSED = "sentry.webhook.inbox.processed"
        const val OUTCOME_DONE = "done"
        const val OUTCOME_SKIPPED = "skipped"
        const val OUTCOME_RETRY = "retry"
        const val OUTCOME_FAILED = "failed"
    }
}
