package org.studieojavry.iamapi.auth.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "iam.jwt")
data class JwtProperties(

    @field:NotBlank
    @field:Size(min = 64, message = "jwt secret must be at least 64 chars (HS256 needs >=256 bits of entropy)")
    val secret: String,

    @field:NotBlank
    val issuer: String,

    @field:NotBlank
    val audience: String,

    @field:Min(60)
    val accessTokenTtlSeconds: Long = 900,

    /**
     * rememberMe = true (자동 로그인 ON). 영속 쿠키.
     */
    @field:Min(3600)
    val rememberMeRefreshTtlSeconds: Long = 60 * 60 * 24 * 30,

    /**
     * rememberMe = false (자동 로그인 OFF). 브라우저 세션 쿠키 + 짧은 TTL.
     */
    @field:Min(600)
    val sessionRefreshTtlSeconds: Long = 60 * 60 * 8
) {
    fun refreshTtlSecondsFor(rememberMe: Boolean): Long =
        if (rememberMe) rememberMeRefreshTtlSeconds else sessionRefreshTtlSeconds
}
