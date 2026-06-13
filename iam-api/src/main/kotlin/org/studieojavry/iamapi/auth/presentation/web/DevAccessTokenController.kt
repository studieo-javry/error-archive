package org.studieojavry.iamapi.auth.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.iamapi.auth.application.port.JwtIssuerPort
import org.studieojavry.iamapi.auth.config.JwtProperties
import java.time.Instant

/**
 * **local profile 전용** — OAuth 흐름 우회로 *임의 userId* 로 access token 발급.
 * SSE / 알림 / 잔디 등 다른 e2e 검증의 *진입 토큰* 으로 사용.
 *
 * dev/stg/prod 에서는 `@Profile("local")` 로 bean 등록 자체 X.
 * SecurityConfig 가 local 한정 `/__dev/` permitAll 처리.
 *
 * 사용 예:
 *   curl -X POST 'http://localhost:8080/__dev/auth/issue-access?userId=1'
 *   → {"accessToken":"eyJ...","expiresAt":"2026-..."}
 */
@Profile("local")
@Tag(name = "dev-fixture", description = "local profile 한정 — 검증용 access token 발급.")
@RestController
@RequestMapping("/__dev/auth")
class DevAccessTokenController(
    private val jwtIssuer: JwtIssuerPort,
    private val jwtProperties: JwtProperties,
) {
    @Operation(summary = "[dev] access token 발급 — OAuth 우회")
    @PostMapping("/issue-access")
    fun issueAccess(@RequestParam userId: Long): Map<String, Any> {
        val expiresAt = Instant.now().plusSeconds(jwtProperties.accessTokenTtlSeconds)
        val token = jwtIssuer.issueAccessToken(userId, expiresAt)
        return mapOf("accessToken" to token, "expiresAt" to expiresAt.toString())
    }
}
