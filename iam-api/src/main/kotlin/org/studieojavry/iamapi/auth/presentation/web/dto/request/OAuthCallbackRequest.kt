package org.studieojavry.iamapi.auth.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "OAuth 콜백 요청 — provider 가 돌려준 code/state 를 받아 토큰 발급.")
data class OAuthCallbackRequest(
    @field:Schema(
        description = "provider 가 SPA 로 돌려준 authorization code.",
        example = "a3f1b2c...",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotBlank
    val code: String,

    @field:Schema(
        description = "authorize 단계에서 발급한 state. state 쿠키와 *상수시간 비교* 일치해야 (CSRF 방어).",
        example = "randomstatetoken",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotBlank
    val state: String,

    @field:Schema(
        description = "authorize 단계와 동일한 redirectUri.",
        example = "https://app.example.com/oauth/callback",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotBlank
    val redirectUri: String,

    @field:Schema(
        description = "true 면 refresh 토큰을 *persistent* 쿠키로 보관 (브라우저 재시작 후 유지). 기본 false=세션 쿠키.",
        example = "true",
        defaultValue = "false",
    )
    val rememberMe: Boolean = false,
)
