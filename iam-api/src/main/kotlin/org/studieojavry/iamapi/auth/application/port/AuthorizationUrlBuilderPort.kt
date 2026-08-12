package org.studieojavry.iamapi.auth.application.port

import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider

interface AuthorizationUrlBuilderPort {
    fun supports(provider: SocialProvider): Boolean
    fun build(state: String, redirectUri: String): String
}