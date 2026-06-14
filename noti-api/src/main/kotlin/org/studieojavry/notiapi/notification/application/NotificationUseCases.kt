package org.studieojavry.notiapi.notification.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.notiapi.notification.application.sender.EmailSenderPort
import org.studieojavry.notiapi.notification.application.sender.PushSenderPort
import org.studieojavry.notiapi.notification.domain.Notification
import org.studieojavry.notiapi.notification.domain.NotificationSettings
import org.studieojavry.notiapi.notification.domain.NotificationType
import tools.jackson.databind.ObjectMapper

/**
 * core-api 의 멘션 이벤트를 받아 in-app row 적재 + email + push 까지 fan-out.
 *
 * 동작:
 *  1. 사용자별 NotificationSettings 조회 (없으면 default)
 *  2. masterEnabled=false 면 *모든 채널 skip*
 *  3. inApp.mentions=true  → 알림 row 적재
 *  4. email.mentions=true  → iam-api 로 email 조회 → SMTP 발송
 *  5. inApp.mentions=true  → device tokens 조회 → push 발송  (push 토글이 별도 없으므로 inApp 토글 재사용)
 *
 * 발송 채널 실패(iam 조회/SMTP/FCM)는 *로그 후 swallow* — 한 채널 장애가 다른 채널을 막지 않게.
 */
/**
 * in-app row 적재 — Spring 의 self-invocation 제약 때문에 별도 @Service 로 분리해서
 * 외부에서 호출해야 @Transactional 이 실제 적용된다.
 */
@Service
class InAppMentionWriter(
    private val notifications: NotificationRepositoryPort,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun save(event: ReceiveMentionEventUseCase.MentionEvent, targets: List<Pair<Long, NotificationSettings>>) {
        val rows = targets
            .filter { (_, s) -> s.masterEnabled && s.inApp.mentions }
            .map { (uid, _) ->
                val payload = objectMapper.writeValueAsString(MentionPayloadJson(
                    errorCaseId = event.errorCaseId,
                    commentId = event.commentId,
                    snippet = event.snippet.take(200),
                ))
                Notification.create(
                    recipientUserId = uid,
                    type = NotificationType.MENTION_IN_COMMENT,
                    actorUserId = event.actorUserId,
                    payload = payload,
                )
            }
        if (rows.isNotEmpty()) notifications.saveAll(rows)
    }

    private data class MentionPayloadJson(
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )
}

@Service
class ReceiveMentionEventUseCase(
    private val settings: NotificationSettingsRepositoryPort,
    private val deviceTokens: DeviceTokenRepositoryPort,
    private val iamUserContact: IamUserContactPort,
    private val emailSender: EmailSenderPort,
    private val pushSender: PushSenderPort,
    private val inAppWriter: InAppMentionWriter,
) {
    private val log = KotlinLogging.logger {}

    /**
     * in-app 적재는 트랜잭션, email/push 는 트랜잭션 밖에서 외부 호출.
     */
    fun invoke(event: MentionEvent) {
        val targets = event.recipientUserIds.distinct().map { uid ->
            val s = settings.findByUserId(uid) ?: NotificationSettings.defaults()
            uid to s
        }
        inAppWriter.save(event, targets)
        targets.forEach { (uid, s) ->
            if (!s.masterEnabled) return@forEach
            if (s.email.mentions) sendEmailSafe(uid, event)
            if (s.inApp.mentions) sendPushSafe(uid, event)
        }
    }

    private fun sendEmailSafe(userId: Long, event: MentionEvent) {
        try {
            val contact = iamUserContact.fetch(userId) ?: run {
                log.debug { "[mention-email] no contact for userId=$userId — skip" }
                return
            }
            if (!contact.active) return
            val email = contact.email ?: return
            val subject = "${contact.displayName.ifBlank { "Someone" }} mentioned you"
            val text = "${contact.displayName} (id=${event.actorUserId}) mentioned you in a comment.\n" +
                "\n\"${event.snippet.take(200)}\"\n\nOpen: /error-cases/${event.errorCaseId}#comment-${event.commentId}"
            val html = "<p><b>${contact.displayName}</b> mentioned you in a comment.</p>" +
                "<blockquote>${escapeHtml(event.snippet.take(200))}</blockquote>" +
                "<p><a href=\"/error-cases/${event.errorCaseId}#comment-${event.commentId}\">Open</a></p>"
            emailSender.send(EmailSenderPort.EmailMessage(
                to = email, subject = subject, htmlBody = html, textBody = text,
            ))
        } catch (e: Exception) {
            log.warn(e) { "[mention-email] send failed for userId=$userId — swallowed" }
        }
    }

    private fun sendPushSafe(userId: Long, event: MentionEvent) {
        try {
            val tokens = deviceTokens.listByUserId(userId).map { it.token }
            if (tokens.isEmpty()) return
            pushSender.send(PushSenderPort.PushMessage(
                tokens = tokens,
                title = "New mention",
                body = event.snippet.take(120),
                data = mapOf(
                    "type" to "MENTION_IN_COMMENT",
                    "errorCaseId" to event.errorCaseId.toString(),
                    "commentId" to event.commentId.toString(),
                    "actorUserId" to event.actorUserId.toString(),
                ),
            ))
        } catch (e: Exception) {
            log.warn(e) { "[mention-push] send failed for userId=$userId — swallowed" }
        }
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    data class MentionEvent(
        val recipientUserIds: List<Long>,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )
}

/**
 * 내 알림 inbox.
 */
@Service
class ListMyNotificationsUseCase(
    private val notifications: NotificationRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long, unreadOnly: Boolean, limit: Int, offset: Int): List<Notification> =
        notifications.findByUserId(userId, unreadOnly, limit.coerceIn(1, 100), offset.coerceAtLeast(0))
}

@Service
class CountMyUnreadUseCase(
    private val notifications: NotificationRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long): Long = notifications.countUnread(userId)
}

@Service
class MarkMyNotificationReadUseCase(
    private val notifications: NotificationRepositoryPort,
) {
    @Transactional
    fun invoke(userId: Long, notificationId: Long): Boolean =
        notifications.markRead(notificationId, userId) > 0

    @Transactional
    fun invokeAll(userId: Long): Int =
        notifications.markAllRead(userId)
}
