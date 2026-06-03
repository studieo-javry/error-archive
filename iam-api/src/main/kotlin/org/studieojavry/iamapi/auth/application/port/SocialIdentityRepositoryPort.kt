package org.studieojavry.iamapi.auth.application.port

import org.studieojavry.iamapi.auth.domain.model.SocialIdentity
import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider

interface SocialIdentityRepositoryPort {
    fun findByProviderAndProviderUserId(provider: SocialProvider, providerUserId: String): SocialIdentity?
    fun save(identity: SocialIdentity): SocialIdentity

    /** 회원 탈퇴 finalize 시 호출. 사용자에게 연결된 모든 OAuth identity 삭제. */
    fun deleteByUserId(userId: Long)

    /** 한 사용자의 모든 OAuth 연결. Settings → Account → "Login & connections" 표시용. */
    fun findAllByUserId(userId: Long): List<SocialIdentity>
}
