package org.studieojavry.iamapi.auth.application.port

import java.time.Instant

interface JwtIssuerPort {

    fun issueAccessToken(userId: Long, expiresAt: Instant): String

    /**
     * SSE 채널 전용 단명 ticket — `typ=sse` 로 일반 access 토큰과 격리.
     * Authorization header 를 보낼 수 없는 `EventSource` 가 URL query 로 첨부 (`?ticket=...`).
     * 동일 sign key + iss + aud 를 쓰지만 typ 으로 구별되어 일반 endpoint 의 인증으로 사용 불가.
     */
    fun issueSseTicket(userId: Long, expiresAt: Instant): String

    data class IssuedAccessToken(val token: String, val expiresAt: Instant)
}