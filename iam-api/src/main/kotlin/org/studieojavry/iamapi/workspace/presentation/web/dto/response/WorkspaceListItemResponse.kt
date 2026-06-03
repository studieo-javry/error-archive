package org.studieojavry.iamapi.workspace.presentation.web.dto.response

data class WorkspaceListItemResponse(
    val workspaceId: Long,
    val name: String,
    val role: String,
    val createdByUserId: Long
)