package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort
import tools.jackson.databind.ObjectMapper

/**
 * Outbox 기반 activity event publisher.
 *
 * 호출 시점: 도메인 UseCase 의 `@Transactional` 안 — outbox INSERT 가 도메인 INSERT 와 *같은 트랜잭션*.
 * commit 후 [OutboxRelayer] 가 발견 → Kafka send.
 *
 * `noti.publisher.mode=kafka` 일 때만 활성화. 그 외엔 [NoopActivityEventPublisherAdapter] 가 fallback.
 *
 * 발행 실패는 *INSERT 자체가 도메인 트랜잭션과 묶임* — JSON 직렬화 외엔 실패 경로 거의 없음.
 * 그래도 안전을 위해 try/catch + log warn 으로 마감 (fire-and-forget 유지).
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class OutboxActivityEventPublisherAdapter(
    private val outboxRepo: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : ActivityEventPublisherPort {

    private val log = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

    override fun publish(event: ActivityEventPublisherPort.ActivityEvent) {
        try {
            val msg = ActivityMessage(
                userId = event.userId,
                type = event.type.name,
                occurredAt = event.occurredAt.toString(),
                score = 0,
                idempotencyKey = event.idempotencyKey,
                meta = event.meta,
                origin = event.origin?.name,
            )
            val payload = objectMapper.writeValueAsString(msg)
            outboxRepo.save(
                OutboxEventEntity(
                    aggregateType = event.type.name.substringBefore('_'),  // CASE / STEP / SOLUTION / COMMENT
                    aggregateId = event.idempotencyKey,
                    topic = TOPIC,
                    kafkaKey = event.userId.toString(),
                    payload = payload,
                )
            )
            // 실시간 쓰기경로 카운터 — 활동 종류·출처별 발행량(rate()). insight 집계 파이프라인과 독립이라
            // "쓰기경로엔 찍혔는데 insight 에 안 들어옴"(파이프라인 장애)을 교차검증하는 운영 신호도 된다.
            Counter.builder("ea.activity.published")
                .tag("type", event.type.name)
                .tag("origin", event.origin?.name ?: "USER")
                .description("core-api 쓰기경로에서 발행된 사용자 활동 수")
                .register(meterRegistry)
                .increment()
        } catch (ex: Exception) {
            log.warn(ex) { "outbox activity INSERT failed (silently dropped): key=${event.idempotencyKey}" }
        }
    }

    /** Kafka 메시지 페이로드 — insight-api `ActivityEventConsumer.ActivityMessage` 와 동일 스키마. */
    data class ActivityMessage(
        val userId: Long,
        val type: String,
        val occurredAt: String,
        val score: Int,
        val idempotencyKey: String,
        val meta: Map<String, Any?>? = null,
        /** null 이면 USER(평소 집계). "AGENT_IMPORT" 면 consumer 가 daily 집계에서 제외. */
        val origin: String? = null,
    )

    companion object {
        const val TOPIC = "user-activity.v1"
    }
}
