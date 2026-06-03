package org.studieojavry.coreapi.errorcase.shared.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "core.iam-api")
data class IamApiProperties(
    @field:NotBlank
    val baseUrl: String
)