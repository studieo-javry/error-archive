package org.studieojavry.iamapi.social.presentation.web.dto.response

data class UserSummaryResponse(
    val userId: Long,
    val handle: String,
    val displayName: String,
    val avatarUrl: String?
)
