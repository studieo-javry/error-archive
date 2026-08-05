package org.studieojavry.notiapi.notification.infrastructure.kafka

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.infrastructure.dlq.NotificationDlqSink

/**
 * `notification-events.invitations.v1` 토픽 consumer.
 * 처리 로직은 [KafkaEventProcessor] 위임, 실패는 [NotificationDlqSink] 격리(자동 재처리).
 */
@Component
class WorkspaceInvitationEventConsumer(
    private val processor: KafkaEventProcessor,
    private val dlqSink: NotificationDlqSink,
) {
    @KafkaListener(topics = ["notification-events.invitations.v1"], groupId = "noti-api")
    fun onInvitation(value: String) {
        try {
            processor.dispatch(KafkaEventType.WORKSPACE_INVITATION, value)
        } catch (ex: Exception) {
            dlqSink.record(KafkaEventType.WORKSPACE_INVITATION, value, ex)
        }
    }
}
