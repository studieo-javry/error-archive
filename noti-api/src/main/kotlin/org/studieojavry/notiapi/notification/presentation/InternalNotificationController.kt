package org.studieojavry.notiapi.notification.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.notiapi.notification.application.ReceiveMentionEventUseCase
import org.studieojavry.notiapi.notification.presentation.dto.IncomingMentionRequest

/**
 * **Internal-only** — core-api 가 동기 호출로 멘션 알림을 적재.
 * gateway 가 라우팅하지 않는 prefix(`/internal/...`) — 외부 노출 X.
 * principal = caller 의 *internal JWT* sub (core-api 가 보낸 userId)
 */
@Tag(name = "notifications-internal", description = "service-to-service: 알림 적재.")
@RestController
@RequestMapping("/internal/notifications")
class InternalNotificationController(
    private val receiveMentionEventUseCase: ReceiveMentionEventUseCase,
) {

    @Operation(
        summary = "멘션 알림 적재",
        description = """
            core-api 가 댓글 작성 후 멘션 대상자에게 in-app 알림을 적재하기 위해 호출.
            각 recipient 의 NotificationSettings(`inApp.mentions` + `masterEnabled`)를 검사 후 row 적재.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "수신됨 — 일부 사용자는 설정에 따라 적재 안 될 수 있음"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @PostMapping("/mentions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun receiveMention(@RequestBody request: IncomingMentionRequest) {
        receiveMentionEventUseCase.invoke(ReceiveMentionEventUseCase.MentionEvent(
            recipientUserIds = request.recipientUserIds,
            actorUserId = request.actorUserId,
            errorCaseId = request.errorCaseId,
            commentId = request.commentId,
            snippet = request.snippet,
        ))
    }
}
