package org.studieojavry.notiapi.notification.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.studieojavry.notiapi.notification.application.ListMyNotificationsUseCase
import org.studieojavry.notiapi.notification.application.ReceiveCommentOnCaseEventUseCase
import org.studieojavry.notiapi.notification.application.ReceiveMentionEventUseCase
import org.studieojavry.notiapi.notification.application.ReceiveNewFollowerEventUseCase
import org.studieojavry.notiapi.notification.application.ReceiveReplyEventUseCase
import org.studieojavry.notiapi.notification.application.ReceiveWorkspaceInvitationEventUseCase
import org.studieojavry.notiapi.notification.infrastructure.sse.NotificationSseBroker
import org.studieojavry.notiapi.notification.presentation.dto.NotificationResponse
import tools.jackson.databind.ObjectMapper

/**
 * **local profile 전용** — internal-auth 토큰 없이 SSE + 멘션 알림 흐름을 검증하기 위한 fixture.
 *
 * - prod/stg/dev 에서는 `@Profile("local")` 로 bean 자체가 등록되지 않아 외부 노출 0.
 * - SecurityConfig 가 local 한정 `/__dev/` permitAll → 토큰 없이도 호출.
 *
 * 사용 예 (별도 터미널 2 개):
 *  T1 (SSE 구독):
 *    curl -N "http://localhost:8082/__dev/notifications/stream?userId=999"
 *  T2 (멘션 발사):
 *    curl -X POST "http://localhost:8082/__dev/notifications/fire-mention?recipient=999&actor=1&snippet=hi"
 */
@Profile("local")
@Tag(name = "dev-fixture", description = "local profile 한정 — SSE / 멘션 발화 헬퍼.")
@RestController
@RequestMapping("/__dev/notifications")
class DevNotificationFixtureController(
    private val sseBroker: NotificationSseBroker,
    private val receiveMention: ReceiveMentionEventUseCase,
    private val receiveReply: ReceiveReplyEventUseCase,
    private val receiveFollower: ReceiveNewFollowerEventUseCase,
    private val receiveInvitation: ReceiveWorkspaceInvitationEventUseCase,
    private val receiveCommentOnCase: ReceiveCommentOnCaseEventUseCase,
    private val listMyNotifications: ListMyNotificationsUseCase,
    private val objectMapper: ObjectMapper,
) {

    @Operation(summary = "[dev] list with optional since (catchup) — production endpoint 의 since 분기 검증용")
    @GetMapping("/list")
    fun listForUser(
        @RequestParam userId: Long,
        @RequestParam(required = false) since: Long?,
        @RequestParam(required = false, defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
    ): List<NotificationResponse> {
        val rows = if (since != null) {
            listMyNotifications.invokeSince(userId, since, unreadOnly, limit)
        } else {
            listMyNotifications.invoke(userId, unreadOnly, limit, 0)
        }
        return rows.map { NotificationResponse.from(it, objectMapper) }
    }
    @Operation(summary = "[dev] SSE 구독 — userId 를 query 로 받음")
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestParam userId: Long): SseEmitter = sseBroker.subscribe(userId)

    @Operation(summary = "[dev] 멘션 알림 발사 — recipient 의 in-app row + SSE push 검증")
    @PostMapping("/fire-mention")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun fireMention(
        @RequestParam recipient: Long,
        @RequestParam(defaultValue = "1") actor: Long,
        @RequestParam(defaultValue = "1") errorCaseId: Long,
        @RequestParam(defaultValue = "1") commentId: Long,
        @RequestParam(defaultValue = "test mention from dev fixture") snippet: String,
    ) {
        receiveMention.invoke(ReceiveMentionEventUseCase.MentionEvent(
            recipientUserIds = listOf(recipient),
            actorUserId = actor,
            errorCaseId = errorCaseId,
            commentId = commentId,
            snippet = snippet,
        ))
    }

    @Operation(summary = "[dev] 답글 알림 발사 — parent comment author 에게")
    @PostMapping("/fire-reply")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun fireReply(
        @RequestParam recipient: Long,
        @RequestParam(defaultValue = "1") actor: Long,
        @RequestParam(defaultValue = "1") errorCaseId: Long,
        @RequestParam(defaultValue = "1") commentId: Long,
        @RequestParam(defaultValue = "1") parentCommentId: Long,
        @RequestParam(defaultValue = "test reply from dev fixture") snippet: String,
    ) {
        receiveReply.invoke(ReceiveReplyEventUseCase.ReplyEvent(
            recipientUserId = recipient,
            actorUserId = actor,
            errorCaseId = errorCaseId,
            commentId = commentId,
            parentCommentId = parentCommentId,
            snippet = snippet,
        ))
    }

    @Operation(summary = "[dev] 내 글 댓글 알림 발사 — ErrorCase author 에게 (최상위 댓글)")
    @PostMapping("/fire-comment-on-case")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun fireCommentOnCase(
        @RequestParam recipient: Long,
        @RequestParam(defaultValue = "1") actor: Long,
        @RequestParam(defaultValue = "1") errorCaseId: Long,
        @RequestParam(defaultValue = "1") commentId: Long,
        @RequestParam(defaultValue = "test comment on your case") snippet: String,
    ) {
        receiveCommentOnCase.invoke(ReceiveCommentOnCaseEventUseCase.CommentOnCaseEvent(
            recipientUserId = recipient,
            actorUserId = actor,
            errorCaseId = errorCaseId,
            commentId = commentId,
            snippet = snippet,
        ))
    }

    @Operation(summary = "[dev] 새 팔로워 알림 발사 — followee 에게")
    @PostMapping("/fire-follower")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun fireFollower(
        @RequestParam recipient: Long,
        @RequestParam(defaultValue = "1") follower: Long,
    ) {
        receiveFollower.invoke(ReceiveNewFollowerEventUseCase.NewFollowerEvent(
            recipientUserId = recipient,
            followerUserId = follower,
        ))
    }

    @Operation(summary = "[dev] 워크스페이스 초대 알림 발사 — invitee 에게 (in-app 만)")
    @PostMapping("/fire-invitation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun fireInvitation(
        @RequestParam recipient: Long,
        @RequestParam(defaultValue = "1") workspaceId: Long,
        @RequestParam(defaultValue = "Demo Workspace") workspaceName: String,
        @RequestParam(defaultValue = "1") invitationId: Long,
        @RequestParam(defaultValue = "1") invitedBy: Long,
    ) {
        receiveInvitation.invoke(ReceiveWorkspaceInvitationEventUseCase.WorkspaceInvitationEvent(
            recipientUserId = recipient,
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            invitationId = invitationId,
            invitedByUserId = invitedBy,
        ))
    }
}
