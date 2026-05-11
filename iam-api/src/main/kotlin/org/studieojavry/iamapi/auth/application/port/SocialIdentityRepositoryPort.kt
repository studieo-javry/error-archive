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

    /** 한 사용자의 OAuth 연결 수. Disconnect 가드(마지막 수단 보호)에 사용. */
    fun countByUserId(userId: Long): Long

    /** 본인이 해당 provider 와 연결돼 있는가. Disconnect 시 *404 vs 409* 분기에 사용. */
    fun existsByUserIdAndProvider(userId: Long, provider: SocialProvider): Boolean

    /** 단건 provider 삭제. 삭제된 row 수(0 또는 1). 마지막 수단 가드는 use case 책임. */
    fun deleteByUserIdAndProvider(userId: Long, provider: SocialProvider): Int
}
