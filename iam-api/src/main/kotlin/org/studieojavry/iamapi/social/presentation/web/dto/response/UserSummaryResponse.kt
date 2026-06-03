package org.studieojavry.iamapi.social.presentation.web.dto.response

data class UserSummaryResponse(
    val userId: Long,
    val displayName: String,
    val avatarUrl: String?
)
