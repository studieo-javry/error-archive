package org.studieojavry.notiapi.notification.infrastructure.kafka

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.infrastructure.dlq.NotificationDlqSink

/**
 * `notification-events.mentions.v1` 토픽 consumer.
 * 처리 로직은 [KafkaEventProcessor] 에 위임(재시도 잡과 공유). 실패 시 [NotificationDlqSink] 로 격리 후
 * 정상 return(offset commit) → PARSE=DEAD 즉시 / INGEST=PENDING 자동 재처리.
 */
@Component
class MentionEventConsumer(
    private val processor: KafkaEventProcessor,
    private val dlqSink: NotificationDlqSink,
) {
    @KafkaListener(topics = ["notification-events.mentions.v1"], groupId = "noti-api")
    fun onMention(value: String) {
        try {
            processor.dispatch(KafkaEventType.MENTION, value)
        } catch (ex: Exception) {
            dlqSink.record(KafkaEventType.MENTION, value, ex)
        }
    }
}
