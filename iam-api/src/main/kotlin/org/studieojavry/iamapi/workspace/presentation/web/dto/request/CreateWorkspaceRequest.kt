package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateWorkspaceRequest(
    @field:NotBlank
    @field:Size(min = 1, max = 50)
    val name: String
)