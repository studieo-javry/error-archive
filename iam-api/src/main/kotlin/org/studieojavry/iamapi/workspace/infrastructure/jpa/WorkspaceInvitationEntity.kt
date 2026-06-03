package org.studieojavry.iamapi.workspace.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceInvitation
import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationStatus
import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationType
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

@Entity
@Table(
    name = "iam_workspace_invitation",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_iam_workspace_invitation_token", columnNames = ["token_hash"])
    ],
    indexes = [
        Index(name = "ix_iam_workspace_invitation_workspace", columnList = "workspace_id"),
        Index(name = "ix_iam_workspace_invitation_status", columnList = "status")
    ]
)
class WorkspaceInvitationEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    var workspaceId: Long,

    @Column(name = "invited_by_user_id", nullable = false)
    var invitedByUserId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    var type: InvitationType,

    @Column(name = "email", length = 254)
    var email: String?,

    @Column(name = "token_hash", nullable = false, length = 128)
    var tokenHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    var role: WorkspaceRole,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: InvitationStatus,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "accepted_at")
    var acceptedAt: Instant?,

    @Column(name = "accepted_by_user_id")
    var acceptedByUserId: Long?
) {

    fun toDomain(): WorkspaceInvitation = WorkspaceInvitation.rehydrate(
        id = id!!,
        workspaceId = workspaceId,
        invitedByUserId = invitedByUserId,
        type = type,
        email = email,
        tokenHash = tokenHash,
        role = role,
        status = status,
        expiresAt = expiresAt,
        createdAt = createdAt,
        acceptedAt = acceptedAt,
        acceptedByUserId = acceptedByUserId
    )

    companion object {
        fun fromDomain(invitation: WorkspaceInvitation): WorkspaceInvitationEntity =
            WorkspaceInvitationEntity(
                id = invitation.id,
                workspaceId = invitation.workspaceId,
                invitedByUserId = invitation.invitedByUserId,
                type = invitation.type,
                email = invitation.email,
                tokenHash = invitation.tokenHash,
                role = invitation.role,
                status = invitation.status,
                expiresAt = invitation.expiresAt,
                createdAt = invitation.createdAt,
                acceptedAt = invitation.acceptedAt,
                acceptedByUserId = invitation.acceptedByUserId
            )
    }
}