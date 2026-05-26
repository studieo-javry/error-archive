package org.studieojavry.iamapi.workspace.application.command

import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

data class ChangeMemberRoleCommand(
    val workspaceId: Long,
    val actorUserId: Long,
    val targetUserId: Long,
    val newRole: WorkspaceRole
)