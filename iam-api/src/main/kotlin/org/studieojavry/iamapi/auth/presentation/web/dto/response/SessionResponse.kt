package org.studieojavry.iamapi.auth.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "활성 세션 1건. 한 refresh-token family = 한 디바이스 세션.")
data class SessionResponse(
    @field:Schema(description = "세션 식별자(family UUID). DELETE 시 path 에 사용.")
    val sessionId: UUID,
    @field:Schema(description = "사람이 읽는 라벨 (예: 'macOS · Chrome 132').", example = "macOS · Chrome 132")
    val deviceLabel: String?,
    @field:Schema(description = "원본 User-Agent. 라벨이 부족할 때 fallback.")
    val userAgent: String?,
    @field:Schema(description = "발급 시점 IP (v4/v6).")
    val ipAddress: String?,
    @field:Schema(description = "세션 시작(첫 로그인) 시각.")
    val createdAt: Instant,
    @field:Schema(description = "마지막 refresh 시각. null=발급 후 한 번도 refresh 안 됨.")
    val lastUsedAt: Instant?,
    @field:Schema(description = "이 토큰 만료 시각. 만료 직전이면 곧 자동 종료.")
    val expiresAt: Instant,
    @field:Schema(description = "Remember-me 옵션으로 발급됐는지 (장기 세션).")
    val rememberMe: Boolean,
)