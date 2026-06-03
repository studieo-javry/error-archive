package org.studieojavry.gateway.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "iam.jwt")
data class JwtProperties(
    @field:NotBlank
    @field:Size(min = 64, message = "jwt secret must be at least 64 chars (HS256 needs >=256 bits of entropy)")
    val secret: String,

    @field:NotBlank
    val issuer: String,

    @field:NotBlank
    val audience: String
)
