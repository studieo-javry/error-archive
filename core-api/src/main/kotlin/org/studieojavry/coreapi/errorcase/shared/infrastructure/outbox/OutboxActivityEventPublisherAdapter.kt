package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * Outbox 기반 activity event publisher.
 *
 * 호출 시점: 도메인 UseCase 의 `@Transactional` 안 — outbox INSERT 가 도메인 INSERT 와 *같은 트랜잭션*.
 * commit 후 [OutboxRelayer] 가 발견 → Kafka send.
 *
 * `noti.publisher.mode=kafka` 일 때만 활성화. 그 외엔 [NoopActivityEventPublisherAdapter] 가 fallback.
 *
 * ## 실패 정책 (절충)
 *  - **직렬화 실패([JacksonException])만 삼킨다** — payload 직렬화는 결정론적 코드 버그라 재시도해도 실패.
 *    분석용 이벤트 버그로 도메인 작업(예: Case import)을 막지 않도록 warn 로그 후 drop.
 *  - **INSERT 실패(DB·인프라)는 전파한다** — outbox INSERT 가 도메인 트랜잭션과 묶여 있으므로, 전파하면
 *    도메인 변경과 **함께 롤백**(outbox 원자성 보존). 전이적 DB 장애 시 "커밋됐는데 이벤트만 유실"을 방지.
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class OutboxActivityEventPublisherAdapter(
    private val outboxRepo: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
) : ActivityEventPublisherPort {

    private val log = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

    override fun publish(event: ActivityEventPublisherPort.ActivityEvent) {
        val msg = ActivityMessage(
            userId = event.userId,
            type = event.type.name,
            occurredAt = event.occurredAt.toString(),
            score = 0,
            idempotencyKey = event.idempotencyKey,
            meta = event.meta,
            origin = event.origin?.name,
        )
        // 직렬화 실패만 삼킨다(결정론적 버그 → 도메인 작업을 막지 않음). INSERT 실패는 아래에서 전파.
        val payload = try {
            objectMapper.writeValueAsString(msg)
        } catch (ex: JacksonException) {
            log.warn(ex) { "outbox activity payload serialization failed (silently dropped): key=${event.idempotencyKey}" }
            return
        }
        // INSERT 실패(DB·인프라)는 전파 → 도메인 트랜잭션과 함께 롤백(outbox 원자성).
        outboxRepo.save(
            OutboxEventEntity(
                aggregateType = event.type.name.substringBefore('_'),  // CASE / STEP / SOLUTION / COMMENT
                aggregateId = event.idempotencyKey,
                topic = TOPIC,
                kafkaKey = event.userId.toString(),
                payload = payload,
            )
        )
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
