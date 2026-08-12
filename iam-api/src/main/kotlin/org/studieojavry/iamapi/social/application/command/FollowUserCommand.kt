package org.studieojavry.iamapi.social.application.command

data class FollowUserCommand(
    val followerId: Long,
    val followeeId: Long
)
