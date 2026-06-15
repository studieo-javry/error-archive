package org.studieojavry.iamapi.workspace.application.command

data class CreateWorkspaceCommand(
    val createdByUserId: Long,
    val name: String,
    val slug: String,
)
