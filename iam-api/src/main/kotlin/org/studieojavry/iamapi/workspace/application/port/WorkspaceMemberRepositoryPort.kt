package org.studieojavry.iamapi.workspace.application.port

import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

interface WorkspaceMemberRepositoryPort {
    fun save(member: WorkspaceMember): WorkspaceMember
    fun findByWorkspaceIdAndUserId(workspaceId: Long, userId: Long): WorkspaceMember?
    fun findByWorkspaceId(workspaceId: Long): List<WorkspaceMember>
    fun findByUserId(userId: Long): List<WorkspaceMember>
    fun countByWorkspaceIdAndRole(workspaceId: Long, role: WorkspaceRole): Long
    fun delete(workspaceId: Long, userId: Long): Boolean
    fun deleteAllByWorkspaceId(workspaceId: Long)
}