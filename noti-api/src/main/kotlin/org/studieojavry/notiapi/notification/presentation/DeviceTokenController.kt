package org.studieojavry.notiapi.notification.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.notiapi.notification.application.RegisterDeviceTokenUseCase
import org.studieojavry.notiapi.notification.application.RemoveDeviceTokenUseCase

@Tag(name = "device-tokens", description = "FCM 디바이스 토큰 — 푸시 알림 발송 대상.")
@RestController
@RequestMapping("/api/v1/users/me/device-tokens")
class DeviceTokenController(
    private val registerDeviceTokenUseCase: RegisterDeviceTokenUseCase,
    private val removeDeviceTokenUseCase: RemoveDeviceTokenUseCase,
) {
    @Operation(summary = "디바이스 토큰 등록 (멱등)",
        description = "앱 첫 실행 / 토큰 갱신 시 호출. 동일 (userId, token) 면 그대로 반환.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "등록됨(또는 이미 있음)"),
        ApiResponse(responseCode = "400", description = "platform 잘못됨 / token blank", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @Valid @RequestBody request: RegisterDeviceTokenRequest,
    ) {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        try {
            registerDeviceTokenUseCase.invoke(uid, request.token, request.platform)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
    }

    @Operation(summary = "디바이스 토큰 제거", description = "로그아웃/앱 삭제/토큰 무효화 시. 없는 토큰이어도 멱등 204.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "제거됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @DeleteMapping("/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @PathVariable token: String,
    ) {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        removeDeviceTokenUseCase.invoke(uid, token)
    }
}

@Schema(description = "디바이스 토큰 등록 요청.")
data class RegisterDeviceTokenRequest(
    @field:Schema(description = "FCM 토큰 (앱이 발급)", example = "f9aDk-w8...", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 512)
    @field:NotBlank @field:Size(max = 512)
    val token: String,

    @field:Schema(description = "IOS / ANDROID / WEB", example = "ANDROID", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank
    val platform: String,
)
