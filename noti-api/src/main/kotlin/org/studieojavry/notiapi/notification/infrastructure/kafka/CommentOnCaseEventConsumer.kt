package org.studieojavry.notiapi.notification.infrastructure.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.ReceiveCommentOnCaseEventUseCase
import tools.jackson.databind.ObjectMapper

/**
 * `notification-events.comments-on-case.v1` 토픽 consumer.
 * core-api 가 *최상위* 댓글 작성 시 발화 (ErrorCase author 가 recipient). 단건 처리.
 */
@Component
class CommentOnCaseEventConsumer(
    private val receiveCommentOnCaseEventUseCase: ReceiveCommentOnCaseEventUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = ["notification-events.comments-on-case.v1"], groupId = "noti-api")
    fun onCommentOnCase(value: String) {
        try {
            val msg = objectMapper.readValue(value, CommentOnCaseMessage::class.java)
            receiveCommentOnCaseEventUseCase.invoke(ReceiveCommentOnCaseEventUseCase.CommentOnCaseEvent(
                recipientUserId = msg.recipientUserId,
                actorUserId = msg.actorUserId,
                errorCaseId = msg.errorCaseId,
                commentId = msg.commentId,
                snippet = msg.snippet,
            ))
        } catch (ex: Exception) {
            log.error(ex) { "failed to process comment-on-case event: $value" }
            throw ex
        }
    }

    data class CommentOnCaseMessage(
        val recipientUserId: Long,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )
}
