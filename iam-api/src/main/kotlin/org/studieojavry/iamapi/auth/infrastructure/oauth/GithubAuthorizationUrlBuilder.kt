package org.studieojavry.iamapi.auth.infrastructure.oauth

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import org.studieojavry.iamapi.auth.application.port.AuthorizationUrlBuilderPort
import org.studieojavry.iamapi.auth.config.OAuthProperties
import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider

@Component
class GithubAuthorizationUrlBuilder(
    private val oauthProperties: OAuthProperties
) : AuthorizationUrlBuilderPort {

    private val log = KotlinLogging.logger {}

    override fun supports(provider: SocialProvider): Boolean = provider == SocialProvider.GITHUB

    override fun build(state: String, redirectUri: String): String {
        val github = oauthProperties.github
        log.info {
            "github.clientId ${github.clientId}, state: $state, scope: $github.scope, redirectUri: $redirectUri"
        }
        return UriComponentsBuilder.fromUriString(github.authorizeEndpoint)
            .queryParam("client_id", github.clientId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("scope", github.scope)
            .queryParam("state", state)
            .queryParam("allow_signup", "true")
            .encode()
            .build()
            .toUriString()
    }
}
