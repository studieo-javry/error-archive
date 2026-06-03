package org.studieojavry.coreapi.errorcase.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "core.attachment")
data class AttachmentStorageProperties(
    @field:NotBlank
    val storagePath: String,

    @field:NotBlank
    val publicBaseUrl: String
)
