package org.studieojavry.iamapi.auth.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "iam.oauth")
data class OAuthProperties(
    @field:Valid
    val github: GithubOAuthProperties
) {

    data class GithubOAuthProperties(
        @field:NotBlank val clientId: String,
        @field:NotBlank val clientSecret: String,
        val authorizeEndpoint: String = "https://github.com/login/oauth/authorize",
        val tokenEndpoint: String = "https://github.com/login/oauth/access_token",
        val userEndpoint: String = "https://api.github.com/user",
        val emailEndpoint: String = "https://api.github.com/user/emails",
        val scope: String = "read:user user:email"
    )
}