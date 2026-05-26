package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.port.WorkspaceInvitationRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort

@Service
class DeleteWorkspaceUseCase(
    private val workspaceRepository: WorkspaceRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort,
    private val invitationRepository: WorkspaceInvitationRepositoryPort
) {

    @Transactional
    fun invoke(workspaceId: Long, actorUserId: Long) {
        workspaceRepository.findById(workspaceId)
            ?: throw NoSuchElementException("workspace not found: $workspaceId")
        WorkspaceAccess.requireAdmin(memberRepository, workspaceId, actorUserId)

        invitationRepository.deleteAllByWorkspaceId(workspaceId)
        memberRepository.deleteAllByWorkspaceId(workspaceId)
        workspaceRepository.delete(workspaceId)
    }
}