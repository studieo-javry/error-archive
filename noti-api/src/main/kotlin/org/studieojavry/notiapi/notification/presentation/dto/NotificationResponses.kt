package org.studieojavry.notiapi.notification.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import org.studieojavry.notiapi.notification.domain.Notification
import org.studieojavry.notiapi.notification.domain.NotificationType
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Schema(description = "알림 1건 (in-app inbox).")
data class NotificationResponse(
    val id: Long,
    val type: NotificationType,
    val actorUserId: Long?,
    val payload: Map<String, Any?>,
    val readAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        fun from(n: Notification, om: ObjectMapper): NotificationResponse {
            @Suppress("UNCHECKED_CAST")
            val payload = runCatching { om.readValue(n.payload, Map::class.java) as Map<String, Any?> }
                .getOrDefault(emptyMap())
            return NotificationResponse(
                id = n.id!!, type = n.type, actorUserId = n.actorUserId,
                payload = payload, readAt = n.readAt, createdAt = n.createdAt,
            )
        }
    }
}

@Schema(description = "core-api → noti-api 멘션 알림 적재 요청 (internal).")
data class IncomingMentionRequest(
    val recipientUserIds: List<Long>,
    val actorUserId: Long,
    val errorCaseId: Long,
    val commentId: Long,
    val snippet: String,
)

@Schema(description = "unread count 응답.")
data class UnreadCountResponse(val count: Long)
