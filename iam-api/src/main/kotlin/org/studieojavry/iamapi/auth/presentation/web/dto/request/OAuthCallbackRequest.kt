package org.studieojavry.iamapi.auth.presentation.web.dto.request

import jakarta.validation.constraints.NotBlank

data class OAuthCallbackRequest(
    @field:NotBlank val code: String,
    @field:NotBlank val state: String,
    @field:NotBlank val redirectUri: String,
    val rememberMe: Boolean = false
)
