package org.studieojavry.iamapi.workspace.infrastructure.jpa

import org.springframework.stereotype.Repository
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

@Repository
class WorkspaceMemberRepositoryAdapter(
    private val jpa: WorkspaceMemberJpaRepository
) : WorkspaceMemberRepositoryPort {

    override fun save(member: WorkspaceMember): WorkspaceMember =
        jpa.save(WorkspaceMemberEntity.fromDomain(member)).toDomain()

    override fun findByWorkspaceIdAndUserId(workspaceId: Long, userId: Long): WorkspaceMember? =
        jpa.findByWorkspaceIdAndUserId(workspaceId, userId)?.toDomain()

    override fun findByWorkspaceId(workspaceId: Long): List<WorkspaceMember> =
        jpa.findByWorkspaceId(workspaceId).map { it.toDomain() }

    override fun findByUserId(userId: Long): List<WorkspaceMember> =
        jpa.findByUserId(userId).map { it.toDomain() }

    override fun countByWorkspaceIdAndRole(workspaceId: Long, role: WorkspaceRole): Long =
        jpa.countByWorkspaceIdAndRole(workspaceId, role)

    override fun delete(workspaceId: Long, userId: Long): Boolean =
        jpa.deleteRelation(workspaceId, userId) > 0

    override fun deleteAllByWorkspaceId(workspaceId: Long) {
        jpa.deleteAllByWorkspaceId(workspaceId)
    }
}