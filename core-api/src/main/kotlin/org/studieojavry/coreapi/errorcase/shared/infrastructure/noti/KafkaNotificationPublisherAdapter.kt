package org.studieojavry.coreapi.errorcase.shared.infrastructure.noti

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.shared.application.port.NotificationPublisherPort
import tools.jackson.databind.ObjectMapper

/**
 * Kafka 기반 알림 publisher — `notification-events.mentions.v1` 토픽.
 *
 * - **default 구현체** (config `noti.publisher.mode: kafka`).
 * - key = recipientUserId 첫 번째 (파티션 분산은 user 단위로) — 단, 한 이벤트에 여러 recipient 이 있을 수 있어
 *   *recipient 별로 메시지 1건* 으로 fan-out 하여 publish (consumer 처리 단순화).
 * - 메시지 value = JSON. consumer 가 동일 스키마로 역직렬화.
 *
 * 실패 시 *조용히 fallback* — 알림 누락이 댓글 작성을 막아선 안 됨. mention row 는 DB 에 있어
 * 추후 outbox/재처리 가능.
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class KafkaNotificationPublisherAdapter(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) : NotificationPublisherPort {

    private val log = KotlinLogging.logger {}

    override fun publishMentions(event: NotificationPublisherPort.MentionEvent) {
        if (event.recipientUserIds.isEmpty()) return
        event.recipientUserIds.distinct().forEach { recipient ->
            val msg = MentionMessage(
                recipientUserId = recipient,
                actorUserId = event.actorUserId,
                errorCaseId = event.errorCaseId,
                commentId = event.commentId,
                snippet = event.snippet,
            )
            try {
                val json = objectMapper.writeValueAsString(msg)
                kafkaTemplate.send(TOPIC, recipient.toString(), json)
            } catch (ex: Exception) {
                log.warn(ex) { "kafka mention publish failed (silently dropped): recipient=$recipient" }
            }
        }
    }

    /** Kafka 메시지 페이로드 — consumer 와 동일 스키마. */
    data class MentionMessage(
        val recipientUserId: Long,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )

    companion object {
        const val TOPIC = "notification-events.mentions.v1"
    }
}
