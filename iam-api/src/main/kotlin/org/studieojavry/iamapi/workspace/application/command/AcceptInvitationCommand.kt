package org.studieojavry.iamapi.workspace.application.command

data class AcceptInvitationCommand(
    val token: String,
    val acceptingUserId: Long
)