package org.studieojavry.iamapi.auth.application.port

import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider

interface SocialProfileFetcherPort {

    fun supports(provider: SocialProvider): Boolean

    fun fetch(credential: ProviderCredential): SocialProfile

    sealed interface ProviderCredential {
        data class AuthorizationCode(val code: String, val redirectUri: String) : ProviderCredential
        data class IdToken(val idToken: String) : ProviderCredential
    }

    data class SocialProfile(
        val providerUserId: String,
        val email: String?,
        val displayName: String,
        val profileUrl: String?,
        val avatarUrl: String?
    )
}