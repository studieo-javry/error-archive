package org.studieojavry.notiapi.notification.infrastructure.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.ReceiveMentionEventUseCase
import tools.jackson.databind.ObjectMapper

/**
 * `notification-events.mentions.v1` 토픽 consumer.
 *
 * core-api 가 댓글 작성 후 *recipient 별 fan-out* 으로 메시지 발행 — consumer 는 *단건 처리*.
 * 처리 중 예외는 자동 재시도(Spring Kafka 기본) 후 *DLQ* (추후 도입).
 */
@Component
class MentionEventConsumer(
    private val receiveMentionEventUseCase: ReceiveMentionEventUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = ["notification-events.mentions.v1"], groupId = "noti-api")
    fun onMention(value: String) {
        try {
            val msg = objectMapper.readValue(value, MentionMessage::class.java)
            receiveMentionEventUseCase.invoke(ReceiveMentionEventUseCase.MentionEvent(
                recipientUserIds = listOf(msg.recipientUserId),
                actorUserId = msg.actorUserId,
                errorCaseId = msg.errorCaseId,
                commentId = msg.commentId,
                snippet = msg.snippet,
            ))
        } catch (ex: Exception) {
            log.error(ex) { "failed to process mention event: $value" }
            throw ex   // Spring Kafka 재시도 → 결국 DLQ (추후 설정)
        }
    }

    data class MentionMessage(
        val recipientUserId: Long,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )
}
