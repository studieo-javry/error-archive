package org.studieojavry.notiapi.notification.infrastructure.kafka

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.infrastructure.dlq.NotificationDlqSink

/**
 * `notification-events.comments-on-case.v1` 토픽 consumer.
 * 처리 로직은 [KafkaEventProcessor] 위임, 실패는 [NotificationDlqSink] 격리(자동 재처리).
 */
@Component
class CommentOnCaseEventConsumer(
    private val processor: KafkaEventProcessor,
    private val dlqSink: NotificationDlqSink,
) {
    @KafkaListener(topics = ["notification-events.comments-on-case.v1"], groupId = "noti-api")
    fun onCommentOnCase(value: String) {
        try {
            processor.dispatch(KafkaEventType.COMMENT_ON_CASE, value)
        } catch (ex: Exception) {
            dlqSink.record(KafkaEventType.COMMENT_ON_CASE, value, ex)
        }
    }
}
