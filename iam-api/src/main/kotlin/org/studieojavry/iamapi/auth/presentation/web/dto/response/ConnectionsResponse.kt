package org.studieojavry.iamapi.auth.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "내 계정에 연결된 OAuth identity 1건. provider 내부 식별자는 노출 안 함.")
data class ConnectionResponse(
    @field:Schema(description = "OAuth 프로바이더 코드", example = "GITHUB")
    val provider: String,
    @field:Schema(description = "provider 에 등록된 이메일(있을 때)")
    val providerEmail: String?,
    @field:Schema(description = "provider 의 프로필 URL")
    val profileUrl: String?,
    val linkedAt: Instant,
)
