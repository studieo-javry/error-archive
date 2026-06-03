package org.studieojavry.iamapi.auth.presentation.web.dto.request

import jakarta.validation.constraints.NotBlank

data class StartOAuthRequest(
    @field:NotBlank val redirectUri: String,
    // 로그인 성공 후 SPA 가 복귀할 앱 내부 경로(예: "/my-page"). 콜백 응답의 redirectTo 로 되돌려준다.
    val returnUrl: String? = null
)
