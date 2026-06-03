package org.studieojavry.iamapi.social.presentation.web.dto.response

data class FollowActionResponse(
    val followerId: Long,
    val followeeId: Long,
    val following: Boolean,
    val changed: Boolean
)