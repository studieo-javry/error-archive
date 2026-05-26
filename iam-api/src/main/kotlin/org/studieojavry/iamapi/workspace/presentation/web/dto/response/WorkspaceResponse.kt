package org.studieojavry.iamapi.workspace.presentation.web.dto.response

import java.time.Instant

data class WorkspaceResponse(
    val workspaceId: Long,
    val name: String,
    val createdByUserId: Long,
    val notificationEnabled: Boolean,
    val defaultTimezone: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val viewerRole: String
)