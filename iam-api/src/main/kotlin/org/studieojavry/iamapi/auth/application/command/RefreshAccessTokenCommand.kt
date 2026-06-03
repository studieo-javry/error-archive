package org.studieojavry.iamapi.auth.application.command

data class RefreshAccessTokenCommand(
    val refreshToken: String
)