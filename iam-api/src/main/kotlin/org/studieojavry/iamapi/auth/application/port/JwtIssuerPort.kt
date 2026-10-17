package org.studieojavry.iamapi.auth.application.port

import java.time.Instant

interface JwtIssuerPort {

    fun issueAccessToken(userId: Long, expiresAt: Instant): String

    /**
     * 게스트("로그인 없이 둘러보기") 전용 access 토큰. `typ=access` 로 일반 토큰과 동일하게 통과하되
     * `roles=["GUEST"]` claim 을 실어, gateway 가 내부 토큰으로 전파 → core 에서 `ROLE_GUEST` authority 로 보인다.
     * refresh 토큰은 발급하지 않는다(짧은 TTL + 만료 시 재버튼).
     */
    fun issueGuestAccessToken(userId: Long, expiresAt: Instant): String

    /**
     * SSE 채널 전용 단명 ticket — `typ=sse` 로 일반 access 토큰과 격리.
     * Authorization header 를 보낼 수 없는 `EventSource` 가 URL query 로 첨부 (`?ticket=...`).
     * 동일 sign key + iss + aud 를 쓰지만 typ 으로 구별되어 일반 endpoint 의 인증으로 사용 불가.
     */
    fun issueSseTicket(userId: Long, expiresAt: Instant): String

    data class IssuedAccessToken(val token: String, val expiresAt: Instant)
}