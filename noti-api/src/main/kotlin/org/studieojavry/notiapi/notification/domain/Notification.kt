package org.studieojavry.notiapi.notification.domain

import java.time.Instant

/**
 * 사용자에게 도달한 알림 1건. in-app inbox 의 row.
 * type 별로 payload 구조가 다름 (자유 JSON) — FE 가 type 따라 렌더.
 */
class Notification private constructor(
    val id: Long?,
    val recipientUserId: Long,
    val type: NotificationType,
    val actorUserId: Long?,
    /** type 별 자유 페이로드 (JSON 문자열). */
    val payload: String,
    var readAt: Instant?,
    val createdAt: Instant,
) {
    val isUnread: Boolean get() = readAt == null

    fun markRead(at: Instant = Instant.now()) {
        if (readAt == null) readAt = at
    }

    companion object {
        fun create(
            recipientUserId: Long,
            type: NotificationType,
            actorUserId: Long?,
            payload: String,
            at: Instant = Instant.now(),
        ): Notification = Notification(
            id = null,
            recipientUserId = recipientUserId,
            type = type,
            actorUserId = actorUserId,
            payload = payload,
            readAt = null,
            createdAt = at,
        )

        fun rehydrate(
            id: Long,
            recipientUserId: Long,
            type: NotificationType,
            actorUserId: Long?,
            payload: String,
            readAt: Instant?,
            createdAt: Instant,
        ) = Notification(id, recipientUserId, type, actorUserId, payload, readAt, createdAt)
    }
}

enum class NotificationType {
    MENTION_IN_COMMENT,
    // 추후: COMMENT_REPLY, NEW_FOLLOWER, WORKSPACE_INVITATION, PRODUCT_ANNOUNCEMENT, SECURITY_ALERT
}
