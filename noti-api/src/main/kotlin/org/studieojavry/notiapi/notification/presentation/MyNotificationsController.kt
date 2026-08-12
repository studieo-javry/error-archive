package org.studieojavry.notiapi.notification.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.studieojavry.notiapi.notification.application.CountMyUnreadUseCase
import org.studieojavry.notiapi.notification.application.ListMyNotificationsUseCase
import org.studieojavry.notiapi.notification.application.MarkMyNotificationReadUseCase
import org.studieojavry.notiapi.notification.infrastructure.sse.NotificationSseBroker
import org.studieojavry.notiapi.notification.presentation.dto.NotificationResponse
import org.studieojavry.notiapi.notification.presentation.dto.UnreadCountResponse
import tools.jackson.databind.ObjectMapper

@Tag(
    name = "notifications-me",
    description = "내 알림 inbox + 카운터 + 읽음 처리."
)
@RestController
@RequestMapping("/api/v1/users/me/notifications")
class MyNotificationsController(
    private val listMyNotificationsUseCase: ListMyNotificationsUseCase,
    private val countMyUnreadUseCase: CountMyUnreadUseCase,
    private val markMyNotificationReadUseCase: MarkMyNotificationReadUseCase,
    private val sseBroker: NotificationSseBroker,
    private val objectMapper: ObjectMapper,
) {

    @Operation(
        summary = "내 알림 목록",
        description = """
            최신순(createdAt DESC). `unreadOnly=true` 면 안 읽은 것만.
            **SSE catchup**: `since=<notificationId>` 가 있으면 *그 id 이후* row 만 (offset 무시) —
            FE 가 SSE disconnect 후 마지막 받은 id 를 기억해 재연결 시 호출. offset/limit 의 일반 페이지네이션과 공존.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @Parameter(description = "안 읽은 것만", example = "false")
        @RequestParam(required = false, defaultValue = "false") unreadOnly: Boolean,
        @Parameter(description = "1..100", example = "20")
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @Parameter(description = "≥0 (since 와 함께 쓰면 무시)", example = "0")
        @RequestParam(required = false, defaultValue = "0") offset: Int,
        @Parameter(description = "SSE catchup — id > since 인 row 만", example = "42")
        @RequestParam(required = false) since: Long?,
    ): List<NotificationResponse> {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        val rows = if (since != null) {
            listMyNotificationsUseCase.invokeSince(uid, since, unreadOnly, limit)
        } else {
            listMyNotificationsUseCase.invoke(uid, unreadOnly, limit, offset)
        }
        return rows.map { NotificationResponse.from(it, objectMapper) }
    }

    @Operation(summary = "내 unread count (종 아이콘 카운터)")
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"))
    @GetMapping("/unread-count")
    fun unreadCount(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
    ): UnreadCountResponse {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        return UnreadCountResponse(count = countMyUnreadUseCase.invoke(uid))
    }

    @Operation(summary = "단건 읽음 처리", description = "본인 row 만. 없거나 이미 읽었으면 멱등 204.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "처리됨(또는 이미 읽음)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markRead(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @PathVariable id: Long,
    ) {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        markMyNotificationReadUseCase.invoke(uid, id)  // 영향 0건이어도 멱등 — 204
    }

    @Operation(summary = "모두 읽음 처리")
    @ApiResponses(ApiResponse(responseCode = "204", description = "처리됨"))
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markAllRead(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
    ) {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        markMyNotificationReadUseCase.invokeAll(uid)
    }

    @Operation(
        summary = "실시간 알림 스트림 (SSE)",
        description = """
            Server-Sent Events 로 *내가 수신한* 알림을 실시간 push.
            FE: `new EventSource('/api/v1/users/me/notifications/stream')` 후 `'notification'` 이벤트 listen.
            payload 는 `NotificationResponse` 와 같은 모양 (id/type/payload/deepLink/createdAt). `readAt` 은 항상 null (방금 도착).
            timeout 없음 — 브라우저 close 또는 네트워크 끊김으로 자동 종료, 재연결은 FE 책임.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "스트림 open"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
    ): SseEmitter {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        return sseBroker.subscribe(uid)
    }
}
