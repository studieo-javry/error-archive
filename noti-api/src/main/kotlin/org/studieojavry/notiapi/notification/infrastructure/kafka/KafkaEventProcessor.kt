package org.studieojavry.notiapi.notification.infrastructure.kafka

import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.ReceiveCaseResolvedEventUseCase
import org.studieojavry.notiapi.notification.application.ReceiveCommentOnCaseEventUseCase
import org.studieojavry.notiapi.notification.application.ReceiveMentionEventUseCase
import org.studieojavry.notiapi.notification.application.ReceiveNewFollowerEventUseCase
import org.studieojavry.notiapi.notification.application.ReceiveReplyEventUseCase
import org.studieojavry.notiapi.notification.application.ReceiveWorkspaceInvitationEventUseCase
import tools.jackson.databind.ObjectMapper

/**
 * 알림 이벤트의 **단일 처리 진입점** — raw JSON → 파싱 → 해당 use case 호출.
 *
 * consumer(정상 경로)와 [org.studieojavry.notiapi.notification.infrastructure.dlq.NotificationDlqRetryJob]
 * (재처리 경로)이 **같은 dispatch 를 공유**해 로직 중복/불일치를 없앤다.
 *
 * 예외 정책: JSON 파싱 실패 → [tools.jackson.core.JacksonException] 계열이 던져짐(호출자가 PARSE 로 분류).
 * use case 실패 → 그 외 예외(호출자가 INGEST 로 분류).
 */
@Component
class KafkaEventProcessor(
    private val mention: ReceiveMentionEventUseCase,
    private val reply: ReceiveReplyEventUseCase,
    private val commentOnCase: ReceiveCommentOnCaseEventUseCase,
    private val caseResolved: ReceiveCaseResolvedEventUseCase,
    private val follow: ReceiveNewFollowerEventUseCase,
    private val invitation: ReceiveWorkspaceInvitationEventUseCase,
    private val mapper: ObjectMapper,
) {
    fun dispatch(type: KafkaEventType, rawPayload: String) {
        when (type) {
            KafkaEventType.MENTION -> {
                val m = mapper.readValue(rawPayload, MentionMessage::class.java)
                mention.invoke(ReceiveMentionEventUseCase.MentionEvent(
                    recipientUserIds = listOf(m.recipientUserId),
                    actorUserId = m.actorUserId,
                    errorCaseId = m.errorCaseId,
                    commentId = m.commentId,
                    snippet = m.snippet,
                ))
            }
            KafkaEventType.REPLY -> {
                val m = mapper.readValue(rawPayload, ReplyMessage::class.java)
                reply.invoke(ReceiveReplyEventUseCase.ReplyEvent(
                    recipientUserId = m.recipientUserId,
                    actorUserId = m.actorUserId,
                    errorCaseId = m.errorCaseId,
                    commentId = m.commentId,
                    parentCommentId = m.parentCommentId,
                    snippet = m.snippet,
                ))
            }
            KafkaEventType.COMMENT_ON_CASE -> {
                val m = mapper.readValue(rawPayload, CommentOnCaseMessage::class.java)
                commentOnCase.invoke(ReceiveCommentOnCaseEventUseCase.CommentOnCaseEvent(
                    recipientUserId = m.recipientUserId,
                    actorUserId = m.actorUserId,
                    errorCaseId = m.errorCaseId,
                    commentId = m.commentId,
                    snippet = m.snippet,
                ))
            }
            KafkaEventType.CASE_RESOLVED -> {
                val m = mapper.readValue(rawPayload, CaseResolvedMessage::class.java)
                caseResolved.invoke(ReceiveCaseResolvedEventUseCase.CaseResolvedEvent(
                    recipientUserIds = m.recipientUserIds,
                    actorUserId = m.actorUserId,
                    errorCaseId = m.errorCaseId,
                    caseTitle = m.caseTitle,
                    workspaceId = m.workspaceId,
                ))
            }
            KafkaEventType.FOLLOW -> {
                val m = mapper.readValue(rawPayload, FollowerMessage::class.java)
                follow.invoke(ReceiveNewFollowerEventUseCase.NewFollowerEvent(
                    recipientUserId = m.recipientUserId,
                    followerUserId = m.followerUserId,
                ))
            }
            KafkaEventType.WORKSPACE_INVITATION -> {
                val m = mapper.readValue(rawPayload, InvitationMessage::class.java)
                invitation.invoke(ReceiveWorkspaceInvitationEventUseCase.WorkspaceInvitationEvent(
                    recipientUserId = m.recipientUserId,
                    workspaceId = m.workspaceId,
                    workspaceName = m.workspaceName,
                    invitationId = m.invitationId,
                    invitedByUserId = m.invitedByUserId,
                ))
            }
        }
    }

    // ── Kafka 메시지 스키마 (core-api / iam-api publisher 와 일치) ──
    data class MentionMessage(
        val recipientUserId: Long,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )

    data class ReplyMessage(
        val recipientUserId: Long,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val parentCommentId: Long,
        val snippet: String,
    )

    data class CommentOnCaseMessage(
        val recipientUserId: Long,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )

    data class CaseResolvedMessage(
        val recipientUserIds: List<Long>,
        val actorUserId: Long,
        val errorCaseId: Long,
        val caseTitle: String,
        val workspaceId: Long?,
    )

    data class FollowerMessage(
        val recipientUserId: Long,
        val followerUserId: Long,
    )

    data class InvitationMessage(
        val recipientUserId: Long,
        val workspaceId: Long,
        val workspaceName: String,
        val invitationId: Long,
        val invitedByUserId: Long,
    )
}
