package org.studieojavry.iamapi.auth.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "OAuth 시작 요청 — provider 별 authorization URL 발급용.")
data class StartOAuthRequest(
    @field:Schema(
        description = "provider 가 인가 코드를 돌려보낼 콜백 URI. provider 콘솔에 등록된 값과 일치해야.",
        example = "https://app.example.com/oauth/callback",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotBlank
    val redirectUri: String,

    @field:Schema(
        description = "로그인 후 SPA 가 복귀할 *앱 내부* 경로. **상대경로만**(`/...`), 길이 ≤512, `//`/`://`/`\\`/공백 거부(open-redirect 방어). 콜백 응답의 `redirectTo` 로 되돌아옴.",
        example = "/my-page",
        maxLength = 512,
    )
    @field:Size(max = 512)
    val returnUrl: String? = null,
)
