package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.port.WorkspaceInvitationRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort

@Service
class RevokeInvitationUseCase(
    private val invitationRepository: WorkspaceInvitationRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort
) {

    @Transactional
    fun invoke(workspaceId: Long, invitationId: Long, actorUserId: Long) {
        WorkspaceAccess.requireAdmin(memberRepository, workspaceId, actorUserId)
        val invitation = invitationRepository.findById(invitationId)
            ?: throw NoSuchElementException("invitation not found: $invitationId")
        if (invitation.workspaceId != workspaceId) {
            throw NoSuchElementException("invitation not found: $invitationId")
        }
        invitation.revoke()
        invitationRepository.save(invitation)
    }
}