package org.studieojavry.iamapi.social.presentation.web.dto.response

data class FollowStatusResponse(
    val userId: Long,
    val followersCount: Long,
    val followingCount: Long,
    val viewerFollowsTarget: Boolean,
    val targetFollowsViewer: Boolean,
    val isMutual: Boolean,
    val isSelf: Boolean
)