package org.studieojavry.iamapi.workspace.domain.model

import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationStatus
import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationType
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

class WorkspaceInvitation private constructor(
    val id: Long?,
    val workspaceId: Long,
    val invitedByUserId: Long,
    val type: InvitationType,
    val email: String?,
    val tokenHash: String,
    val role: WorkspaceRole,
    var status: InvitationStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
    var acceptedAt: Instant?,
    var acceptedByUserId: Long?
) {
    init {
        require(tokenHash.isNotBlank()) { "tokenHash must not be blank" }
        require(role != WorkspaceRole.ADMIN) {
            "ADMIN role cannot be granted via invitation; promote after join"
        }
        when (type) {
            InvitationType.EMAIL -> require(!email.isNullOrBlank()) { "email is required for EMAIL invitation" }
            InvitationType.LINK -> require(email == null) { "LINK invitation must not carry an email" }
        }
    }

    fun isPending(now: Instant = Instant.now()): Boolean =
        status == InvitationStatus.PENDING && now.isBefore(expiresAt)

    fun markExpiredIfNeeded(now: Instant = Instant.now()) {
        if (status == InvitationStatus.PENDING && !now.isBefore(expiresAt)) {
            status = InvitationStatus.EXPIRED
        }
    }

    fun accept(byUserId: Long) {
        check(isPending()) { "invitation is not pending" }
        status = InvitationStatus.ACCEPTED
        acceptedAt = Instant.now()
        acceptedByUserId = byUserId
    }

    fun revoke() {
        if (status.isTerminal()) return
        status = InvitationStatus.REVOKED
    }

    companion object {
        fun create(
            workspaceId: Long,
            invitedByUserId: Long,
            type: InvitationType,
            email: String?,
            tokenHash: String,
            role: WorkspaceRole,
            expiresAt: Instant
        ): WorkspaceInvitation = WorkspaceInvitation(
            id = null,
            workspaceId = workspaceId,
            invitedByUserId = invitedByUserId,
            type = type,
            email = email,
            tokenHash = tokenHash,
            role = role,
            status = InvitationStatus.PENDING,
            expiresAt = expiresAt,
            createdAt = Instant.now(),
            acceptedAt = null,
            acceptedByUserId = null
        )

        fun rehydrate(
            id: Long,
            workspaceId: Long,
            invitedByUserId: Long,
            type: InvitationType,
            email: String?,
            tokenHash: String,
            role: WorkspaceRole,
            status: InvitationStatus,
            expiresAt: Instant,
            createdAt: Instant,
            acceptedAt: Instant?,
            acceptedByUserId: Long?
        ): WorkspaceInvitation = WorkspaceInvitation(
            id, workspaceId, invitedByUserId, type, email, tokenHash, role,
            status, expiresAt, createdAt, acceptedAt, acceptedByUserId
        )
    }
}
