package org.studieojavry.iamapi.auth.presentation.web.dto.response

import java.time.Instant

data class TokenResponse(
    val tokenType: String,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val userId: Long,
    // 로그인 시작 시 넘긴 returnUrl(검증된 앱 내부 경로). SPA 가 로그인 후 이 경로로 이동. 없으면 null.
    val redirectTo: String? = null
)
