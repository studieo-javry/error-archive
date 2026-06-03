package org.studieojavry.iamapi.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "iam.cookie")
data class AuthCookieProperties(
    val refreshTokenName: String = "iam_refresh",
    val oauthStateName: String = "iam_oauth_state",
    val oauthReturnName: String = "iam_oauth_return",
    val domain: String? = null,
    val secure: Boolean = true,
    val sameSite: String = "Lax",
    val path: String = "/api/v1/auth"
)
