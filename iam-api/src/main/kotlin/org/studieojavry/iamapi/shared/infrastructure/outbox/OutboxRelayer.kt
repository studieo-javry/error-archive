package org.studieojavry.iamapi.shared.infrastructure.outbox

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * iam-api outbox relayer. Adaptive polling (busy 200ms / idle backoff → 5s).
 * 자세한 설계: docs/outbox-design.md.
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class OutboxRelayer(
    private val outboxRepo: OutboxEventJpaRepository,
    private val sender: KafkaTopicSender,
    private val properties: OutboxProperties,
    private val taskScheduler: TaskScheduler,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Programmatic 트랜잭션 — Spring 의 @Transactional self-invocation 이슈 회피.
     * `tick()` 이 같은 클래스의 `processBatch()` 를 직접 호출하면 AOP proxy 안 거쳐
     * @Transactional 이 무효화됨. TransactionTemplate 로 명시 트랜잭션 시작.
     */
    private val tx: TransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    private val nextDelayMs = AtomicLong()

    @Volatile
    private var scheduledFuture: ScheduledFuture<*>? = null

    @PostConstruct
    fun start() {
        nextDelayMs.set(properties.idleInitialDelayMs)
        scheduleNext()
        log.info {
            "iam outbox relayer started: busy=${properties.busyDelayMs}ms, idleInitial=${properties.idleInitialDelayMs}ms, idleMax=${properties.idleMaxDelayMs}ms"
        }
    }

    @PreDestroy
    fun stop() {
        scheduledFuture?.cancel(false)
    }

    private fun scheduleNext() {
        val delay = nextDelayMs.get()
        scheduledFuture = taskScheduler.schedule(
            { tick() },
            Instant.now().plusMillis(delay),
        )
    }

    @SchedulerLock(name = "iam-outbox-relay", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    fun tick() {
        try {
            val processed = tx.execute { processBatch() } ?: 0
            adjustNextDelay(processed)
        } catch (ex: Exception) {
            log.error(ex) { "iam outbox relayer tick failed" }
            adjustNextDelay(0)
        } finally {
            scheduleNext()
        }
    }

    /** TransactionTemplate 안에서만 호출. 직접 호출 시 트랜잭션 없음. */
    private fun processBatch(): Int {
        val batch = outboxRepo.lockBatchForRelay(now = Instant.now(), limit = properties.batchSize)
        if (batch.isEmpty()) return 0

        for (row in batch) {
            try {
                sender.send(row.topic, row.kafkaKey, row.payload)
                row.markSent()
            } catch (ex: Exception) {
                handleSendFailure(row, ex)
            }
        }
        return batch.size
    }

    private fun handleSendFailure(row: OutboxEventEntity, ex: Exception) {
        if (row.attempts + 1 >= properties.maxAttempts) {
            row.markDead("max attempts exceeded; last: ${ex.message ?: ex.javaClass.simpleName}")
            log.warn { "iam outbox row DEAD: id=${row.id} topic=${row.topic}" }
        } else {
            val backoffMs = computeBackoffMs(row.attempts + 1)
            row.scheduleRetry(ex.message ?: ex.javaClass.simpleName, Duration.ofMillis(backoffMs))
        }
    }

    private fun computeBackoffMs(attempts: Int): Long {
        val exp = (1L shl minOf(attempts, 20)) * properties.baseBackoffMs
        return minOf(exp, properties.maxBackoffMs)
    }

    private fun adjustNextDelay(processed: Int) {
        if (processed > 0) {
            nextDelayMs.set(properties.busyDelayMs)
        } else {
            val cur = nextDelayMs.get()
            val next = (cur * properties.idleMultiplier).toLong().coerceAtMost(properties.idleMaxDelayMs)
            nextDelayMs.set(next.coerceAtLeast(properties.idleInitialDelayMs))
        }
    }
}
