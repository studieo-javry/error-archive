package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import jakarta.validation.constraints.NotBlank

data class AcceptInvitationRequest(
    @field:NotBlank
    val token: String
)