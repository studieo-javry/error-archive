package org.studieojavry.iamapi.workspace.application.command

data class UpdateWorkspaceCommand(
    val workspaceId: Long,
    val actorUserId: Long,
    val name: String?,
    val notificationEnabled: Boolean?,
    val defaultTimezone: String?
)