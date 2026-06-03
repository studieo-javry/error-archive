package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import jakarta.validation.constraints.NotBlank

data class ChangeMemberRoleRequest(
    @field:NotBlank
    val role: String
)