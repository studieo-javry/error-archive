package org.studieojavry.iamapi.auth.infrastructure.security

import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.studieojavry.iamapi.auth.config.AuthCookieProperties
import java.time.Duration

@Component
class AuthCookieFactory(
    private val cookieProperties: AuthCookieProperties
) {

    /**
     * rememberMe=true → maxAge가 명시된 영속 쿠키. 브라우저를 닫아도 TTL 동안 유지.
     * rememberMe=false → maxAge 미설정 = 세션 쿠키. 브라우저 종료 시 삭제.
     */
    fun refreshTokenCookie(value: String, ttl: Duration, persistent: Boolean): ResponseCookie {
        val builder = baseBuilder(cookieProperties.refreshTokenName, value)
        if (persistent) builder.maxAge(ttl)
        return builder.build()
    }

    fun expiredRefreshTokenCookie(): ResponseCookie =
        baseBuilder(cookieProperties.refreshTokenName, "")
            .maxAge(0)
            .build()

    fun oauthStateCookie(value: String): ResponseCookie =
        baseBuilder(cookieProperties.oauthStateName, value)
            .maxAge(Duration.ofMinutes(5))
            .path("/api/v1/auth/oauth")
            .build()

    fun expiredOAuthStateCookie(): ResponseCookie =
        baseBuilder(cookieProperties.oauthStateName, "")
            .maxAge(0)
            .path("/api/v1/auth/oauth")
            .build()

    /** 로그인 후 복귀 경로(returnUrl)를 OAuth 왕복 동안 보관. state 쿠키와 동일 수명/경로. */
    fun oauthReturnCookie(value: String): ResponseCookie =
        baseBuilder(cookieProperties.oauthReturnName, value)
            .maxAge(Duration.ofMinutes(5))
            .path("/api/v1/auth/oauth")
            .build()

    fun expiredOAuthReturnCookie(): ResponseCookie =
        baseBuilder(cookieProperties.oauthReturnName, "")
            .maxAge(0)
            .path("/api/v1/auth/oauth")
            .build()

    private fun baseBuilder(name: String, value: String): ResponseCookie.ResponseCookieBuilder {
        val builder = ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(cookieProperties.secure)
            .sameSite(cookieProperties.sameSite)
            .path(cookieProperties.path)
        cookieProperties.domain?.takeIf { it.isNotBlank() }?.let { builder.domain(it) }
        return builder
    }
}
