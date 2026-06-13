package org.studieojavry.iamapi.shared.notification.infrastructure.outbox

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.iamapi.shared.infrastructure.outbox.OutboxEventEntity
import org.studieojavry.iamapi.shared.infrastructure.outbox.OutboxEventJpaRepository
import org.studieojavry.iamapi.shared.notification.application.port.NotificationPublisherPort
import tools.jackson.databind.ObjectMapper

/**
 * Outbox 기반 notification publisher (iam-api 측) — `notification-events.follows.v1` + `notification-events.invitations.v1`.
 *
 * 도메인 `@Transactional` 안 INSERT → commit 후 OutboxRelayer 가 Kafka send.
 * `noti.publisher.mode=kafka` 일 때만 활성화. 그 외엔 interface default (no-op) fallback.
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class OutboxNotificationPublisherAdapter(
    private val outboxRepo: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
) : NotificationPublisherPort {

    private val log = KotlinLogging.logger {}

    override fun publishNewFollower(event: NotificationPublisherPort.NewFollowerEvent) {
        try {
            val msg = FollowerMessage(
                recipientUserId = event.recipientUserId,
                followerUserId = event.followerUserId,
            )
            outboxRepo.save(
                OutboxEventEntity(
                    aggregateType = "NEW_FOLLOWER",
                    aggregateId = "follow:${event.followerUserId}->${event.recipientUserId}",
                    topic = FOLLOW_TOPIC,
                    kafkaKey = event.recipientUserId.toString(),
                    payload = objectMapper.writeValueAsString(msg),
                )
            )
        } catch (ex: Exception) {
            log.warn(ex) { "iam outbox follower INSERT failed (silently dropped): recipient=${event.recipientUserId}" }
        }
    }

    override fun publishWorkspaceInvitation(event: NotificationPublisherPort.WorkspaceInvitationEvent) {
        try {
            val msg = InvitationMessage(
                recipientUserId = event.recipientUserId,
                workspaceId = event.workspaceId,
                workspaceName = event.workspaceName,
                invitationId = event.invitationId,
                invitedByUserId = event.invitedByUserId,
            )
            outboxRepo.save(
                OutboxEventEntity(
                    aggregateType = "WORKSPACE_INVITATION",
                    aggregateId = "invitation:${event.invitationId}",
                    topic = INVITATION_TOPIC,
                    kafkaKey = event.recipientUserId.toString(),
                    payload = objectMapper.writeValueAsString(msg),
                )
            )
        } catch (ex: Exception) {
            log.warn(ex) { "iam outbox invitation INSERT failed (silently dropped): invitation=${event.invitationId}" }
        }
    }

    /** noti-api `FollowEventConsumer` 와 동일 스키마. */
    data class FollowerMessage(
        val recipientUserId: Long,
        val followerUserId: Long,
    )

    /** noti-api `WorkspaceInvitationEventConsumer` 와 동일 스키마. */
    data class InvitationMessage(
        val recipientUserId: Long,
        val workspaceId: Long,
        val workspaceName: String,
        val invitationId: Long,
        val invitedByUserId: Long,
    )

    companion object {
        const val FOLLOW_TOPIC = "notification-events.follows.v1"
        const val INVITATION_TOPIC = "notification-events.invitations.v1"
    }
}
