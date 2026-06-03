package org.studieojavry.iamapi.social.presentation.web.dto.response

data class FollowListResponse(
    val items: List<UserSummaryResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)
