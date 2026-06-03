package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class CreateInvitationRequest(
    /** "EMAIL" | "LINK" */
    @field:NotBlank
    val type: String,
    /** "READ" | "WRITE" — ADMIN은 초대 불가 */
    @field:NotBlank
    val role: String,
    @field:Email
    val email: String?,
    @field:Min(1)
    val expiresInHours: Int?
)