package org.studieojavry.iamapi.auth.application.command

data class RefreshAccessTokenCommand(
    val refreshToken: String,
    val deviceLabel: String? = null,
    val userAgent: String? = null,
    val ipAddress: String? = null,
)