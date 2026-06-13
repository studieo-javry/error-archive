package org.studieojavry.notiapi.notification.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.notiapi.notification.application.GetMyNotificationSettingsUseCase
import org.studieojavry.notiapi.notification.application.UpdateMyNotificationSettingsUseCase
import org.studieojavry.notiapi.notification.presentation.dto.NotificationSettingsResponse
import org.studieojavry.notiapi.notification.presentation.dto.UpdateNotificationSettingsRequest

@Tag(
    name = "notifications-me",
    description = """
        Settings → Notifications. 채널(`email`/`inApp`) × 카테고리 매트릭스 + master switch.
        Security alerts 는 양 채널 모두 *강제 on* — 변경 불가.

        본 API 는 게이트웨이를 통해서만 호출 가능 (X-Internal-Auth 헤더 검증).
    """
)
@RestController
@RequestMapping("/api/v1/users/me/notification-settings")
class NotificationController(
    private val getMyNotificationSettingsUseCase: GetMyNotificationSettingsUseCase,
    private val updateMyNotificationSettingsUseCase: UpdateMyNotificationSettingsUseCase,
) {

    @Operation(
        summary = "내 알림 설정 조회",
        description = "본인의 알림 설정. 저장된 row 가 없으면 기본값 응답."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping
    fun mySettings(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
    ): NotificationSettingsResponse {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        return NotificationSettingsResponse.from(getMyNotificationSettingsUseCase.invoke(uid))
    }

    @Operation(
        summary = "내 알림 설정 부분 갱신",
        description = """
            PATCH-style. null/미포함 필드는 유지. 채널 안의 카테고리도 동일하게 부분 갱신 (deep merge).

            **Security alerts 는 본 페이로드에 포함될 수 없음** — DTO 에 필드 자체 없음.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "갱신된 전체 설정"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @PatchMapping
    fun updateMySettings(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @Valid @RequestBody request: UpdateNotificationSettingsRequest,
    ): NotificationSettingsResponse {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        val updated = updateMyNotificationSettingsUseCase.invoke(uid, request.toPatch())
        return NotificationSettingsResponse.from(updated)
    }
}