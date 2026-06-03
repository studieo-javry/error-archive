package org.studieojavry.iamapi.workspace.infrastructure.jpa

import org.springframework.stereotype.Repository
import org.studieojavry.iamapi.workspace.application.port.WorkspaceInvitationRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceInvitation
import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationStatus
import java.time.Instant

@Repository
class WorkspaceInvitationRepositoryAdapter(
    private val jpa: WorkspaceInvitationJpaRepository
) : WorkspaceInvitationRepositoryPort {

    override fun save(invitation: WorkspaceInvitation): WorkspaceInvitation =
        jpa.save(WorkspaceInvitationEntity.fromDomain(invitation)).toDomain()

    override fun claimForAcceptance(tokenHash: String, acceptingUserId: Long, now: Instant): Boolean =
        jpa.claimForAcceptance(
            tokenHash = tokenHash,
            acceptingUserId = acceptingUserId,
            now = now,
            pending = InvitationStatus.PENDING,
            accepted = InvitationStatus.ACCEPTED
        ) == 1

    override fun findById(id: Long): WorkspaceInvitation? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun findByTokenHash(tokenHash: String): WorkspaceInvitation? =
        jpa.findByTokenHash(tokenHash)?.toDomain()

    override fun findPendingByWorkspaceId(workspaceId: Long): List<WorkspaceInvitation> =
        jpa.findByWorkspaceIdAndStatus(workspaceId, InvitationStatus.PENDING).map { it.toDomain() }

    override fun deleteAllByWorkspaceId(workspaceId: Long) {
        jpa.deleteAllByWorkspaceId(workspaceId)
    }
}
