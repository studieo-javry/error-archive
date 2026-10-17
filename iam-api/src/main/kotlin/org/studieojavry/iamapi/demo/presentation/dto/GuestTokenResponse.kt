package org.studieojavry.iamapi.demo.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "게스트 둘러보기 토큰 응답")
data class GuestTokenResponse(
    @field:Schema(description = "토큰 타입", example = "Bearer")
    val tokenType: String,
    @field:Schema(description = "게스트 access 토큰(roles=[GUEST], refresh 없음)")
    val accessToken: String,
    @field:Schema(description = "access 토큰 만료 시각(UTC)")
    val accessTokenExpiresAt: Instant,
    @field:Schema(description = "임시 게스트 사용자 id", example = "4021")
    val userId: Long,
    @field:Schema(description = "진입할 데모 워크스페이스 id", example = "7")
    val workspaceId: Long,
    @field:Schema(description = "데모 워크스페이스 slug", example = "demo")
    val workspaceSlug: String,
    @field:Schema(description = "게스트 표시 이름(작성자 구분용)", example = "게스트-3F9A")
    val displayName: String,
)
