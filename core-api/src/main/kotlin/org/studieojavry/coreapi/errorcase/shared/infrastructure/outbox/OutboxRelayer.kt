package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox

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
 * Outbox row → Kafka send relayer.
 *
 * **Adaptive polling**:
 *  - pending 발견 → 다음 라운드 [OutboxProperties.busyDelayMs] (default 200ms).
 *  - 빈 결과 → 다음 라운드 = `currentIdle * idleMultiplier`, cap [idleMaxDelayMs] (default 5s).
 *
 * **Concurrency 모델**:
 *  - `@SchedulerLock` — 다중 인스턴스 중 1 leader 만 매 라운드 실행.
 *  - 라운드 안에서 `FOR UPDATE SKIP LOCKED` — fallback 안전망 + 향후 worker scale-out 여지.
 *  - 라운드 처리는 *별도 트랜잭션* (`REQUIRES_NEW`) — 각 batch 가 독립 commit.
 *
 * **실패 분류**:
 *  - send 실패 → `scheduleRetry` (backoff). attempts == maxAttempts → DEAD.
 *  - 직렬화/예외 → 동일 처리.
 *
 * mode != kafka 면 빈 등록 안 됨.
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
     * `tick()` 이 같은 객체의 `processBatch()` 를 직접 호출하면 AOP proxy 안 거쳐
     * @Transactional 무효화됨. TransactionTemplate 로 명시 트랜잭션 시작.
     */
    private val tx: TransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** 다음 라운드 지연 (ms). adaptive 결과에 따라 매 라운드 갱신. */
    private val nextDelayMs = AtomicLong()

    @Volatile
    private var scheduledFuture: ScheduledFuture<*>? = null

    @PostConstruct
    fun start() {
        nextDelayMs.set(properties.idleInitialDelayMs)
        scheduleNext()
        log.info {
            "outbox relayer started: busy=${properties.busyDelayMs}ms, idleInitial=${properties.idleInitialDelayMs}ms, idleMax=${properties.idleMaxDelayMs}ms, multiplier=${properties.idleMultiplier}, batch=${properties.batchSize}"
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

    /**
     * 한 라운드.
     * `@SchedulerLock` — 동일 lockName 으로 다중 인스턴스 중 1 leader 만 실행.
     */
    @SchedulerLock(name = "core-outbox-relay", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    fun tick() {
        try {
            val processed = tx.execute { processBatch() } ?: 0
            adjustNextDelay(processed)
        } catch (ex: Exception) {
            log.error(ex) { "outbox relayer tick failed" }
            // tick 자체가 실패하면 idle backoff 로 가정 (broker 장애 등 일시적)
            adjustNextDelay(0)
        } finally {
            scheduleNext()
        }
    }

    /**
     * batch 처리 — *TransactionTemplate 안에서만 호출*.
     * `FOR UPDATE SKIP LOCKED` 가 작동하려면 lock 이 같은 트랜잭션 안에서 유지되어야 하므로
     * programmatic 트랜잭션 컨텍스트 필수.
     */
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
            log.warn { "outbox row DEAD: id=${row.id} topic=${row.topic} aggregate=${row.aggregateType}:${row.aggregateId}" }
        } else {
            val backoffMs = computeBackoffMs(row.attempts + 1)
            row.scheduleRetry(ex.message ?: ex.javaClass.simpleName, Duration.ofMillis(backoffMs))
            log.debug { "outbox row retry scheduled: id=${row.id} attempt=${row.attempts} next=+${backoffMs}ms" }
        }
    }

    private fun computeBackoffMs(attempts: Int): Long {
        val exp = (1L shl minOf(attempts, 20)) * properties.baseBackoffMs
        return minOf(exp, properties.maxBackoffMs)
    }

    /** pending 발견 → busy 모드, 빈 결과 → idle backoff. */
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
