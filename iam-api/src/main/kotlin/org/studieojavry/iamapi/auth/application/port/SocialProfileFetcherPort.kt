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
        /**
         * provider 의 unique login (GitHub login 등). 우리 서비스의 handle 매핑 후보.
         * 매핑 시 소문자 정규화 + 충돌 시 `-2`, `-3` suffix.
         */
        val providerLogin: String,
        val displayName: String,
        val profileUrl: String?,
        val avatarUrl: String?
    )
}
