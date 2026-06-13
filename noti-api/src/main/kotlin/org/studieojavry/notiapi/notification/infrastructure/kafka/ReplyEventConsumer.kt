package org.studieojavry.notiapi.notification.infrastructure.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.ReceiveReplyEventUseCase
import tools.jackson.databind.ObjectMapper

/**
 * `notification-events.replies.v1` 토픽 consumer.
 *
 * core-api 가 댓글 작성 시 parent comment 의 author 를 단일 recipient 로 단건 메시지 발행.
 * 처리 실패는 Spring Kafka 기본 재시도 → DLQ (추후 도입).
 */
@Component
class ReplyEventConsumer(
    private val receiveReplyEventUseCase: ReceiveReplyEventUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = ["notification-events.replies.v1"], groupId = "noti-api")
    fun onReply(value: String) {
        try {
            val msg = objectMapper.readValue(value, ReplyMessage::class.java)
            receiveReplyEventUseCase.invoke(ReceiveReplyEventUseCase.ReplyEvent(
                recipientUserId = msg.recipientUserId,
                actorUserId = msg.actorUserId,
                errorCaseId = msg.errorCaseId,
                commentId = msg.commentId,
                parentCommentId = msg.parentCommentId,
                snippet = msg.snippet,
            ))
        } catch (ex: Exception) {
            log.error(ex) { "failed to process reply event: $value" }
            throw ex
        }
    }

    data class ReplyMessage(
        val recipientUserId: Long,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val parentCommentId: Long,
        val snippet: String,
    )
}
