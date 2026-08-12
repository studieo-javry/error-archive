package org.studieojavry.iamapi.workspace.application.command

import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationType
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

data class InviteMemberCommand(
    val workspaceId: Long,
    val actorUserId: Long,
    val type: InvitationType,
    val email: String?,
    val role: WorkspaceRole,
    val expiresInHours: Int?
)