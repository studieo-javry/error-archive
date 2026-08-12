package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.studieojavry.iamapi.auth.application.port.JwtIssuerPort
import org.studieojavry.iamapi.auth.config.JwtProperties
import java.time.Clock
import java.time.Instant

/**
 * SSE ticket 발급 — 인증된 사용자가 `EventSource` 로 SSE 채널에 붙기 직전 호출.
 *
 * 흐름:
 *   1. FE: access token 으로 `POST /api/v1/auth/sse-ticket` 호출
 *   2. iam-api: 사용자 검증 (gateway 가 이미 access token 검증 후 X-Internal-Auth 발급, 또는 직접 access 검증)
 *   3. 단명 (default 30s, yml 의 sseTicketTtlSeconds) ticket 발급 → typ=sse
 *   4. FE: `new EventSource('.../stream?ticket=<ticket>')` 로 사용. 만료 임박 시 재발급.
 */
@Service
class IssueSseTicketUseCase(
    private val jwtIssuer: JwtIssuerPort,
    private val jwtProperties: JwtProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun invoke(userId: Long): IssuedTicket {
        val expiresAt = Instant.now(clock).plusSeconds(jwtProperties.sseTicketTtlSeconds)
        val token = jwtIssuer.issueSseTicket(userId = userId, expiresAt = expiresAt)
        return IssuedTicket(ticket = token, expiresAt = expiresAt)
    }

    data class IssuedTicket(val ticket: String, val expiresAt: Instant)
}
