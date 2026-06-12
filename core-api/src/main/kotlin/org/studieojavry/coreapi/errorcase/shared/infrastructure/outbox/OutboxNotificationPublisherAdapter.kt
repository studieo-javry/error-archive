package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.shared.application.port.NotificationPublisherPort
import tools.jackson.databind.ObjectMapper

/**
 * Outbox 기반 mention notification publisher — `notification-events.mentions.v1`.
 *
 * fan-out 패턴 보존: 한 MentionEvent → recipientUserId 별 outbox row 1건씩 INSERT.
 * 이렇게 해야 partition 분산 (key = recipientUserId) + consumer 측 처리 단순.
 *
 * `noti.publisher.mode=kafka` 일 때만 활성화. `mode=http` 면 [NotiApiNotificationPublisherAdapter] 가 직접 RPC.
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class OutboxNotificationPublisherAdapter(
    private val outboxRepo: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
) : NotificationPublisherPort {

    private val log = KotlinLogging.logger {}

    override fun publishMentions(event: NotificationPublisherPort.MentionEvent) {
        if (event.recipientUserIds.isEmpty()) return
        event.recipientUserIds.distinct().forEach { recipient ->
            try {
                val msg = MentionMessage(
                    recipientUserId = recipient,
                    actorUserId = event.actorUserId,
                    errorCaseId = event.errorCaseId,
                    commentId = event.commentId,
                    snippet = event.snippet,
                )
                val payload = objectMapper.writeValueAsString(msg)
                outboxRepo.save(
                    OutboxEventEntity(
                        aggregateType = "MENTION",
                        aggregateId = "mention:comment-${event.commentId}:user-$recipient",
                        topic = TOPIC,
                        kafkaKey = recipient.toString(),
                        payload = payload,
                    )
                )
            } catch (ex: Exception) {
                log.warn(ex) { "outbox mention INSERT failed (silently dropped): recipient=$recipient" }
            }
        }
    }

    /** Kafka 메시지 페이로드 — noti-api `MentionEventConsumer` 와 동일 스키마. */
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
