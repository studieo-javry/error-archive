package org.studieojavry.publishapi.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "publish.slug")
data class SlugProperties(
    val length: Int = 8,
)
