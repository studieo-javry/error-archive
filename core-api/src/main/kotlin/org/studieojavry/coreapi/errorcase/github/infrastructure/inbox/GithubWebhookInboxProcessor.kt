package org.studieojavry.coreapi.errorcase.github.infrastructure.inbox

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.github.application.usecase.ProcessGithubWebhookEventUseCase
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import java.time.Duration
import java.time.Instant

/**
 * 받은 편지함 처리기 — 주기적으로 PENDING 을 꺼내 [ProcessGithubWebhookEventUseCase] 로 처리.
 *
 * - `@SchedulerLock`: 다중 인스턴스 중 하나만 실행.
 * - **건별 트랜잭션**: 처리(도메인 변경) + DONE 표시가 한 트랜잭션 → 같이 커밋/롤백. 한 건 실패가 배치 전체를 깨지 않는다.
 * - 실패: 별도 트랜잭션에서 attempts++ · backoff(2^n·base, 상한) · 최대 횟수 넘으면 FAILED(수동 재처리 대상).
 */
@Component
@ConditionalOnGithubEnabled
class GithubWebhookInboxProcessor(
    private val inboxRepo: GithubWebhookInboxJpaRepository,
    private val processEvent: ProcessGithubWebhookEventUseCase,
    private val properties: GithubAppProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelayString = "\${core.github.inbox.poll-delay-ms:2000}")
    @SchedulerLock(name = "github-webhook-inbox", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    fun tick() {
        val ids = inboxRepo.findDueIds(
            GithubWebhookInboxEntity.InboxStatus.PENDING,
            Instant.now(),
            PageRequest.of(0, properties.inbox.batchSize),
        )
        ids.forEach(::processOne)
    }

    fun processOne(deliveryId: String) {
        try {
            tx.executeWithoutResult {
                val row = inboxRepo.findById(deliveryId).orElse(null)
                    ?.takeIf { it.status == GithubWebhookInboxEntity.InboxStatus.PENDING }
                    ?: return@executeWithoutResult
                val result = processEvent.invoke(row.event, row.action, row.installationId, row.payload)
                row.markProcessed(skipped = result == ProcessGithubWebhookEventUseCase.Result.SKIPPED)
            }
        } catch (ex: Exception) {
            recordFailure(deliveryId, ex)
        }
    }

    private fun recordFailure(deliveryId: String, ex: Exception) {
        val reason = ex.message ?: ex.javaClass.simpleName
        tx.executeWithoutResult {
            val row = inboxRepo.findById(deliveryId).orElse(null) ?: return@executeWithoutResult
            if (row.attempts + 1 >= properties.inbox.maxAttempts) {
                row.markFailed(reason)
                log.warn(ex) { "github webhook FAILED: delivery=$deliveryId event=${row.event} attempts=${row.attempts}" }
            } else {
                val exp = (1L shl minOf(row.attempts + 1, 20)) * properties.inbox.baseBackoffMs
                row.scheduleRetry(reason, Duration.ofMillis(minOf(exp, properties.inbox.maxBackoffMs)))
                log.debug { "github webhook retry: delivery=$deliveryId attempt=${row.attempts}" }
            }
        }
    }
}
