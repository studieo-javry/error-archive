package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import jakarta.validation.constraints.Size

data class UpdateWorkspaceRequest(
    @field:Size(min = 1, max = 50)
    val name: String?,
    val notificationEnabled: Boolean?,
    @field:Size(max = 64)
    val defaultTimezone: String?
)