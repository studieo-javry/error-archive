package org.studieojavry.insightapi.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "insight.iam-api")
data class IamApiProperties(
    val baseUrl: String = "http://localhost:8080",
)
