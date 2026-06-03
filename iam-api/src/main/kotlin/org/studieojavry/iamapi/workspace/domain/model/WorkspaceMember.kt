package org.studieojavry.iamapi.workspace.domain.model

import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

class WorkspaceMember private constructor(
    val id: Long?,
    val workspaceId: Long,
    val userId: Long,
    var role: WorkspaceRole,
    val joinedAt: Instant
) {
    fun changeRole(newRole: WorkspaceRole) {
        if (this.role == newRole) return
        this.role = newRole
    }

    companion object {
        fun join(workspaceId: Long, userId: Long, role: WorkspaceRole): WorkspaceMember =
            WorkspaceMember(
                id = null,
                workspaceId = workspaceId,
                userId = userId,
                role = role,
                joinedAt = Instant.now()
            )

        fun rehydrate(
            id: Long,
            workspaceId: Long,
            userId: Long,
            role: WorkspaceRole,
            joinedAt: Instant
        ): WorkspaceMember = WorkspaceMember(id, workspaceId, userId, role, joinedAt)
    }
}
