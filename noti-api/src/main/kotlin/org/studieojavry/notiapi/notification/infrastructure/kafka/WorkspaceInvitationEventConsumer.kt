package org.studieojavry.notiapi.notification.infrastructure.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.ReceiveWorkspaceInvitationEventUseCase
import tools.jackson.databind.ObjectMapper

/**
 * `notification-events.invitations.v1` 토픽 consumer.
 * iam-api 가 워크스페이스 초대 생성 시 발행. in-app 만 적재 (email 은 기존 transactional flow).
 */
@Component
class WorkspaceInvitationEventConsumer(
    private val receiveInvitationEventUseCase: ReceiveWorkspaceInvitationEventUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = ["notification-events.invitations.v1"], groupId = "noti-api")
    fun onInvitation(value: String) {
        try {
            val msg = objectMapper.readValue(value, InvitationMessage::class.java)
            receiveInvitationEventUseCase.invoke(ReceiveWorkspaceInvitationEventUseCase.WorkspaceInvitationEvent(
                recipientUserId = msg.recipientUserId,
                workspaceId = msg.workspaceId,
                workspaceName = msg.workspaceName,
                invitationId = msg.invitationId,
                invitedByUserId = msg.invitedByUserId,
            ))
        } catch (ex: Exception) {
            log.error(ex) { "failed to process workspace invitation event: $value" }
            throw ex
        }
    }

    data class InvitationMessage(
        val recipientUserId: Long,
        val workspaceId: Long,
        val workspaceName: String,
        val invitationId: Long,
        val invitedByUserId: Long,
    )
}
