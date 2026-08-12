package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.command.ChangeMemberRoleCommand
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

@Service
class ChangeMemberRoleUseCase(
    private val memberRepository: WorkspaceMemberRepositoryPort
) {

    @Transactional
    fun invoke(command: ChangeMemberRoleCommand) {
        WorkspaceAccess.requireAdmin(memberRepository, command.workspaceId, command.actorUserId)

        val target = memberRepository.findByWorkspaceIdAndUserId(command.workspaceId, command.targetUserId)
            ?: throw NoSuchElementException("member not found in workspace ${command.workspaceId}: userId=${command.targetUserId}")

        if (target.role == command.newRole) return

        if (target.role == WorkspaceRole.ADMIN && command.newRole != WorkspaceRole.ADMIN) {
            ensureNotLastAdmin(command.workspaceId)
        }
        target.changeRole(command.newRole)
        memberRepository.save(target)
    }

    private fun ensureNotLastAdmin(workspaceId: Long) {
        val adminCount = memberRepository.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)
        if (adminCount <= 1) {
            throw IllegalStateException("workspace must keep at least one ADMIN")
        }
    }
}