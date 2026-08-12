package org.studieojavry.iamapi.auth.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.auth.application.usecase.IssueSseTicketUseCase
import java.time.Instant

/**
 * `EventSource` 는 Authorization 헤더를 보낼 수 없으므로, SSE 채널 인증을 위해
 * 사용자가 *access token 으로* 먼저 이 endpoint 호출 → 단명 ticket 받음 →
 * `new EventSource('.../stream?ticket=<value>')` 로 사용.
 *
 * gateway 의 `BearerTokenResolver` 가 `?ticket=` query 도 fallback 으로 추출하고
 * `JwtClaimValidator("typ")` 가 `access | sse` 둘 다 허용. typ=sse 인 ticket 은
 * *short ttl* (default 30s) 이라 URL 로그/proxy 노출 위험 최소화.
 */
@Tag(name = "auth-sse-ticket", description = "SSE 채널 전용 단명 ticket 발급.")
@RestController
@RequestMapping("/api/v1/auth")
class SseTicketController(
    private val issueSseTicketUseCase: IssueSseTicketUseCase,
) {
    @Operation(
        summary = "SSE ticket 발급",
        description = """
            인증된 사용자만 호출 가능 (access token 필요). 응답으로 받은 ticket 을
            `EventSource('.../stream?ticket=<ticket>')` 로 사용. 만료 임박 시 재발급.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @PostMapping("/sse-ticket")
    fun issue(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
    ): SseTicketResponse {
        val uid = jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid principal")
        val issued = issueSseTicketUseCase.invoke(uid)
        return SseTicketResponse(ticket = issued.ticket, expiresAt = issued.expiresAt)
    }
}

@Schema(description = "SSE ticket 응답.")
data class SseTicketResponse(
    @field:Schema(description = "JWT (typ=sse). EventSource 의 URL query 로 사용.")
    val ticket: String,
    @field:Schema(description = "만료 시각 (UTC). FE 는 만료 임박 (예: 5초 전) 시 재발급 필요.")
    val expiresAt: Instant,
)
