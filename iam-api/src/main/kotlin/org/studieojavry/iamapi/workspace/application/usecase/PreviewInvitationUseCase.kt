package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.shared.util.HashUtils
import org.studieojavry.iamapi.workspace.application.port.WorkspaceInvitationRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

@Service
class PreviewInvitationUseCase(
    private val invitationRepository: WorkspaceInvitationRepositoryPort,
    private val workspaceRepository: WorkspaceRepositoryPort
) {

    @Transactional(readOnly = true)
    fun invoke(token: String): Result {
        val invitation = invitationRepository.findByTokenHash(HashUtils.sha256(token))
            ?: throw NoSuchElementException("invitation not recognized")
        val workspace = workspaceRepository.findById(invitation.workspaceId)
            ?: throw NoSuchElementException("workspace not found")

        return Result(
            workspaceId = workspace.id!!,
            workspaceName = workspace.name.value,
            role = invitation.role,
            expiresAt = invitation.expiresAt,
            usable = invitation.isPending()
        )
    }

    data class Result(
        val workspaceId: Long,
        val workspaceName: String,
        val role: WorkspaceRole,
        val expiresAt: Instant,
        val usable: Boolean
    )
}