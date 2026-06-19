package org.studieojavry.notiapi.notification.infrastructure.kafka

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.infrastructure.dlq.NotificationDlqSink

/**
 * `notification-events.replies.v1` 토픽 consumer.
 * 처리 로직은 [KafkaEventProcessor] 위임, 실패는 [NotificationDlqSink] 격리(자동 재처리).
 */
@Component
class ReplyEventConsumer(
    private val processor: KafkaEventProcessor,
    private val dlqSink: NotificationDlqSink,
) {
    @KafkaListener(topics = ["notification-events.replies.v1"], groupId = "noti-api")
    fun onReply(value: String) {
        try {
            processor.dispatch(KafkaEventType.REPLY, value)
        } catch (ex: Exception) {
            dlqSink.record(KafkaEventType.REPLY, value, ex)
        }
    }
}
