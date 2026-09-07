package org.studieojavry.publishapi.publishment.infrastructure.activity

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.publishapi.publishment.application.port.ActivityEventPublisherPort
import org.studieojavry.publishapi.shared.infrastructure.outbox.OutboxEventEntity
import org.studieojavry.publishapi.shared.infrastructure.outbox.OutboxEventJpaRepository
import tools.jackson.databind.ObjectMapper

/**
 * Outbox 기반 activity event publisher — `user-activity.v1`.
 *
 * 도메인 트랜잭션 안에서 outbox INSERT → commit 후 OutboxRelayer 가 Kafka send.
 * `noti.publisher.mode=kafka` 일 때만 활성화. 그 외엔 Noop fallback.
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class OutboxActivityEventPublisherAdapter(
    private val outboxRepo: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
) : ActivityEventPublisherPort {

    private val log = KotlinLogging.logger {}

    override fun publish(event: ActivityEventPublisherPort.ActivityEvent) {
        try {
            val msg = ActivityMessage(
                userId = event.userId,
                type = event.type.name,
                occurredAt = event.occurredAt.toString(),
                score = 0,
                idempotencyKey = event.idempotencyKey,
                meta = event.meta,
            )
            val payload = objectMapper.writeValueAsString(msg)
            outboxRepo.save(
                OutboxEventEntity(
                    aggregateType = "PUBLISHMENT",
                    aggregateId = event.idempotencyKey,
                    topic = TOPIC,
                    kafkaKey = event.userId.toString(),
                    payload = payload,
                )
            )
        } catch (ex: Exception) {
            log.warn(ex) { "publish outbox activity INSERT failed (silently dropped): key=${event.idempotencyKey}" }
        }
    }

    /** insight-api `ActivityEventConsumer.ActivityMessage` 와 동일 스키마. */
    data class ActivityMessage(
        val userId: Long,
        val type: String,
        val occurredAt: String,
        val score: Int,
        val idempotencyKey: String,
        val meta: Map<String, Any?>? = null,
    )

    companion object {
        const val TOPIC = "user-activity.v1"
    }
}
