package org.studieojavry.iamapi.auth.application.port

import java.time.Instant

interface JwtIssuerPort {

    fun issueAccessToken(userId: Long, expiresAt: Instant): String

    data class IssuedAccessToken(val token: String, val expiresAt: Instant)
}