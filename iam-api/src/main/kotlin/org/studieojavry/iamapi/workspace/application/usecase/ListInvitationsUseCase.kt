package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.port.WorkspaceInvitationRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationType
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

@Service
class ListInvitationsUseCase(
    private val invitationRepository: WorkspaceInvitationRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort
) {

    @Transactional(readOnly = true)
    fun invoke(workspaceId: Long, actorUserId: Long): List<Item> {
        WorkspaceAccess.requireAdmin(memberRepository, workspaceId, actorUserId)
        return invitationRepository.findPendingByWorkspaceId(workspaceId).map {
            Item(
                invitationId = it.id!!,
                type = it.type,
                email = it.email,
                role = it.role,
                expiresAt = it.expiresAt,
                createdAt = it.createdAt
            )
        }
    }

    data class Item(
        val invitationId: Long,
        val type: InvitationType,
        val email: String?,
        val role: WorkspaceRole,
        val expiresAt: Instant,
        val createdAt: Instant
    )
}