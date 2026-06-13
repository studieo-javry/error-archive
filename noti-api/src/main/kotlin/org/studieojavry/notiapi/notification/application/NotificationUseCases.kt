package org.studieojavry.notiapi.notification.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.notiapi.notification.application.event.NotificationCreatedEvent
import org.studieojavry.notiapi.notification.application.sender.EmailSenderPort
import org.studieojavry.notiapi.notification.domain.Notification
import org.studieojavry.notiapi.notification.domain.NotificationSettings
import org.studieojavry.notiapi.notification.domain.NotificationType
import tools.jackson.databind.ObjectMapper

/**
 * **범용** in-app 알림 적재 — type 무관하게 row INSERT 후 NotificationCreatedEvent 발행.
 *
 * Spring 의 self-invocation 제약 때문에 별도 @Service 로 분리되어 있어 외부 UseCase 가 호출해야
 * @Transactional 이 실제 적용된다. 저장 후 row 별로 NotificationCreatedEvent 발행 —
 * `@TransactionalEventListener(AFTER_COMMIT)` 인 `NotificationEventDispatcher` 가 SSE broker push.
 *
 * 토글 검사는 *호출하는 UseCase* 책임 — recipientUserIds 는 이미 필터링된 list 로 받음.
 * payload 는 type 별로 다른 JSON 객체. recipient 마다 다른 경우는 따로 호출.
 */
@Service
class InAppNotificationWriter(
    private val notifications: NotificationRepositoryPort,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Transactional
    fun save(
        type: NotificationType,
        actorUserId: Long?,
        recipientUserIds: List<Long>,
        payload: Any,
    ) {
        if (recipientUserIds.isEmpty()) return
        val payloadJson = objectMapper.writeValueAsString(payload)
        val rows = recipientUserIds.map { uid ->
            Notification.create(
                recipientUserId = uid,
                type = type,
                actorUserId = actorUserId,
                payload = payloadJson,
            )
        }
        val saved = notifications.saveAll(rows)
        saved.forEach { eventPublisher.publishEvent(NotificationCreatedEvent(it)) }
    }
}

/**
 * core-api 의 멘션 이벤트를 받아 in-app row 적재 + email 발송 fan-out.
 *
 * MVP1 채널: **email + in-app (SSE 실시간 포함)**. push (FCM) 는 잠정 deferred.
 *
 * 동작:
 *  1. 사용자별 NotificationSettings 조회 (없으면 default)
 *  2. masterEnabled=false 면 *모든 채널 skip*
 *  3. inApp.mentions=true  → row 적재 → commit 후 SSE push
 *  4. email.mentions=true  → iam-api 로 email 조회 → SMTP 발송
 */
@Service
class ReceiveMentionEventUseCase(
    private val settings: NotificationSettingsRepositoryPort,
    private val iamUserContact: IamUserContactPort,
    private val emailSender: EmailSenderPort,
    private val inAppWriter: InAppNotificationWriter,
) {
    private val log = KotlinLogging.logger {}

    fun invoke(event: MentionEvent) {
        val targets = event.recipientUserIds.distinct().map { uid ->
            val s = settings.findByUserId(uid) ?: NotificationSettings.defaults()
            uid to s
        }
        val inAppRecipients = targets
            .filter { (_, s) -> s.masterEnabled && s.inApp.mentions }
            .map { it.first }
        val payload = MentionPayloadJson(
            errorCaseId = event.errorCaseId,
            commentId = event.commentId,
            snippet = event.snippet.take(200),
        )
        inAppWriter.save(NotificationType.MENTION_IN_COMMENT, event.actorUserId, inAppRecipients, payload)
        targets.forEach { (uid, s) ->
            if (!s.masterEnabled) return@forEach
            if (s.email.mentions) sendEmailSafe(uid, event)
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
                "<blockquote>${EmailEscape.html(event.snippet.take(200))}</blockquote>" +
                "<p><a href=\"/error-cases/${event.errorCaseId}#comment-${event.commentId}\">Open</a></p>"
            emailSender.send(EmailSenderPort.EmailMessage(
                to = email, subject = subject, htmlBody = html, textBody = text,
            ))
        } catch (e: Exception) {
            log.warn(e) { "[mention-email] send failed for userId=$userId — swallowed" }
        }
    }

    data class MentionEvent(
        val recipientUserIds: List<Long>,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )

    internal data class MentionPayloadJson(
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )
}

/** type 별 email 본문 작성 시 공통으로 사용하는 HTML escape. */
internal object EmailEscape {
    fun html(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}

/**
 * 답글 알림 — 부모 댓글 author 에게 *내 댓글에 답글이 달렸음* 전달.
 * 도메인 정책상 mention 과 동일 토글 (`inApp.mentions` + `email.mentions`) 사용.
 */
@Service
class ReceiveReplyEventUseCase(
    private val settings: NotificationSettingsRepositoryPort,
    private val iamUserContact: IamUserContactPort,
    private val emailSender: EmailSenderPort,
    private val inAppWriter: InAppNotificationWriter,
) {
    private val log = KotlinLogging.logger {}

    fun invoke(event: ReplyEvent) {
        val s = settings.findByUserId(event.recipientUserId) ?: NotificationSettings.defaults()
        if (!s.masterEnabled) return
        if (s.inApp.mentions) {
            val payload = ReplyPayloadJson(
                errorCaseId = event.errorCaseId,
                commentId = event.commentId,
                parentCommentId = event.parentCommentId,
                snippet = event.snippet.take(200),
            )
            inAppWriter.save(NotificationType.COMMENT_REPLY, event.actorUserId, listOf(event.recipientUserId), payload)
        }
        if (s.email.mentions) sendEmailSafe(event)
    }

    private fun sendEmailSafe(event: ReplyEvent) {
        try {
            val contact = iamUserContact.fetch(event.recipientUserId) ?: run {
                log.debug { "[reply-email] no contact for userId=${event.recipientUserId} — skip" }
                return
            }
            if (!contact.active) return
            val email = contact.email ?: return
            val subject = "${contact.displayName.ifBlank { "Someone" }} replied to your comment"
            val text = "Your comment received a reply.\n\n\"${event.snippet.take(200)}\"\n\n" +
                "Open: /error-cases/${event.errorCaseId}#comment-${event.commentId}"
            val html = "<p>Your comment received a <b>reply</b>.</p>" +
                "<blockquote>${EmailEscape.html(event.snippet.take(200))}</blockquote>" +
                "<p><a href=\"/error-cases/${event.errorCaseId}#comment-${event.commentId}\">Open</a></p>"
            emailSender.send(EmailSenderPort.EmailMessage(to = email, subject = subject, htmlBody = html, textBody = text))
        } catch (e: Exception) {
            log.warn(e) { "[reply-email] send failed for userId=${event.recipientUserId} — swallowed" }
        }
    }

    data class ReplyEvent(
        val recipientUserId: Long,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val parentCommentId: Long,
        val snippet: String,
    )

    internal data class ReplyPayloadJson(
        val errorCaseId: Long,
        val commentId: Long,
        val parentCommentId: Long,
        val snippet: String,
    )
}

/**
 * 내 글에 누가 *최상위* 댓글 — ErrorCase author 에게 알림.
 * 토글: `inApp.commentsOnMyContent` + `email.commentsOnMyContent` (별도 토글).
 * mention/reply 와 의미 분리 — 사용자가 "내 콘텐츠 engagement" 만 끌 수 있음.
 *
 * 발화 측 (core-api) 이 *중복 회피* 책임:
 *  - actor == errorCase.owner → skip
 *  - mention recipient 에 owner 포함 → skip (mention 만 발송)
 *  - parentCommentId != null (답글) → 발화 안 함 (어차피 reply type 으로 처리)
 */
@Service
class ReceiveCommentOnCaseEventUseCase(
    private val settings: NotificationSettingsRepositoryPort,
    private val iamUserContact: IamUserContactPort,
    private val emailSender: EmailSenderPort,
    private val inAppWriter: InAppNotificationWriter,
) {
    private val log = KotlinLogging.logger {}

    fun invoke(event: CommentOnCaseEvent) {
        val s = settings.findByUserId(event.recipientUserId) ?: NotificationSettings.defaults()
        if (!s.masterEnabled) return
        if (s.inApp.commentsOnMyContent) {
            val payload = CommentOnCasePayloadJson(
                errorCaseId = event.errorCaseId,
                commentId = event.commentId,
                snippet = event.snippet.take(200),
            )
            inAppWriter.save(NotificationType.COMMENT_ON_ERROR_CASE, event.actorUserId, listOf(event.recipientUserId), payload)
        }
        if (s.email.commentsOnMyContent) sendEmailSafe(event)
    }

    private fun sendEmailSafe(event: CommentOnCaseEvent) {
        try {
            val contact = iamUserContact.fetch(event.recipientUserId) ?: return
            if (!contact.active) return
            val email = contact.email ?: return
            val subject = "New comment on your post"
            val text = "Someone commented on your error case.\n\n\"${event.snippet.take(200)}\"\n\n" +
                "Open: /error-cases/${event.errorCaseId}#comment-${event.commentId}"
            val html = "<p>Someone commented on your <b>error case</b>.</p>" +
                "<blockquote>${EmailEscape.html(event.snippet.take(200))}</blockquote>" +
                "<p><a href=\"/error-cases/${event.errorCaseId}#comment-${event.commentId}\">Open</a></p>"
            emailSender.send(EmailSenderPort.EmailMessage(to = email, subject = subject, htmlBody = html, textBody = text))
        } catch (e: Exception) {
            log.warn(e) { "[comment-on-case-email] send failed for userId=${event.recipientUserId} — swallowed" }
        }
    }

    data class CommentOnCaseEvent(
        val recipientUserId: Long,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )

    internal data class CommentOnCasePayloadJson(
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )
}

/**
 * 새 팔로워 알림 — 팔로우 *당한* 사용자에게 알림.
 * 토글: `inApp.newFollowers` + `email.newFollowers`.
 * 발화 시점에 follower info 가 따라오지 않으므로 받을 때 `IamUserContactPort` 로 보강.
 */
@Service
class ReceiveNewFollowerEventUseCase(
    private val settings: NotificationSettingsRepositoryPort,
    private val iamUserContact: IamUserContactPort,
    private val emailSender: EmailSenderPort,
    private val inAppWriter: InAppNotificationWriter,
) {
    private val log = KotlinLogging.logger {}

    fun invoke(event: NewFollowerEvent) {
        val s = settings.findByUserId(event.recipientUserId) ?: NotificationSettings.defaults()
        if (!s.masterEnabled) return

        // follower info 보강 — displayName 못 받으면 *fallback name* 으로 알림 자체는 진행.
        val followerContact = runCatching { iamUserContact.fetch(event.followerUserId) }.getOrNull()
        val followerName = followerContact?.displayName?.ifBlank { null } ?: "Someone"

        if (s.inApp.newFollowers) {
            val payload = FollowerPayloadJson(
                followerId = event.followerUserId,
                followerName = followerName,
            )
            inAppWriter.save(NotificationType.NEW_FOLLOWER, event.followerUserId, listOf(event.recipientUserId), payload)
        }
        if (s.email.newFollowers) sendEmailSafe(event, followerName)
    }

    private fun sendEmailSafe(event: NewFollowerEvent, followerName: String) {
        try {
            val contact = iamUserContact.fetch(event.recipientUserId) ?: return
            if (!contact.active) return
            val email = contact.email ?: return
            val subject = "$followerName started following you"
            val text = "$followerName (id=${event.followerUserId}) started following you.\n\nOpen: /users/${event.followerUserId}"
            val html = "<p><b>${EmailEscape.html(followerName)}</b> started following you.</p>" +
                "<p><a href=\"/users/${event.followerUserId}\">Open profile</a></p>"
            emailSender.send(EmailSenderPort.EmailMessage(to = email, subject = subject, htmlBody = html, textBody = text))
        } catch (e: Exception) {
            log.warn(e) { "[follower-email] send failed for userId=${event.recipientUserId} — swallowed" }
        }
    }

    data class NewFollowerEvent(
        val recipientUserId: Long,
        val followerUserId: Long,
    )

    internal data class FollowerPayloadJson(
        val followerId: Long,
        val followerName: String,
    )
}

/**
 * 워크스페이스 초대 알림 — *in-app 채널만*.
 * email 채널은 기존 iam-api 의 transactional invitation email (확인 링크 포함) 이 책임.
 * 토글: `inApp.workspaceInvitations`.
 */
@Service
class ReceiveWorkspaceInvitationEventUseCase(
    private val settings: NotificationSettingsRepositoryPort,
    private val inAppWriter: InAppNotificationWriter,
) {
    fun invoke(event: WorkspaceInvitationEvent) {
        val s = settings.findByUserId(event.recipientUserId) ?: NotificationSettings.defaults()
        if (!s.masterEnabled) return
        if (!s.inApp.workspaceInvitations) return

        val payload = InvitationPayloadJson(
            workspaceId = event.workspaceId,
            workspaceName = event.workspaceName,
            invitationId = event.invitationId,
            invitedByUserId = event.invitedByUserId,
        )
        inAppWriter.save(NotificationType.WORKSPACE_INVITATION, event.invitedByUserId, listOf(event.recipientUserId), payload)
    }

    data class WorkspaceInvitationEvent(
        val recipientUserId: Long,
        val workspaceId: Long,
        val workspaceName: String,
        val invitationId: Long,
        val invitedByUserId: Long,
    )

    internal data class InvitationPayloadJson(
        val workspaceId: Long,
        val workspaceName: String,
        val invitationId: Long,
        val invitedByUserId: Long,
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

    /** SSE 재연결 catchup — `id > sinceId` 의 row 만. */
    @Transactional(readOnly = true)
    fun invokeSince(userId: Long, sinceId: Long, unreadOnly: Boolean, limit: Int): List<Notification> =
        notifications.findByUserIdSince(userId, sinceId, unreadOnly, limit.coerceIn(1, 100))
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
