package org.studieojavry.iamapi.workspace.presentation.web.dto.response

import java.time.Instant

data class CreateInvitationResponse(
    val invitationId: Long,
    val type: String,
    val role: String,
    val expiresAt: Instant,
    /** LINK 타입에만 채워짐. EMAIL 타입은 null (이메일 본문에만 노출) */
    val inviteToken: String?,
    val inviteUrl: String?
)

data class InvitationItemResponse(
    val invitationId: Long,
    val type: String,
    val email: String?,
    val role: String,
    val expiresAt: Instant,
    val createdAt: Instant
)

data class InvitationPreviewResponse(
    val workspaceId: Long,
    val workspaceName: String,
    val role: String,
    val expiresAt: Instant,
    val usable: Boolean
)

data class AcceptInvitationResponse(
    val workspaceId: Long,
    val workspaceName: String,
    val role: String
)