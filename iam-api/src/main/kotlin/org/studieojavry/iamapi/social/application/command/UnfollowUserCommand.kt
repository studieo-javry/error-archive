package org.studieojavry.iamapi.social.application.command

data class UnfollowUserCommand(
    val followerId: Long,
    val followeeId: Long
)
