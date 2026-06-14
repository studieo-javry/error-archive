package org.studieojavry.iamapi.auth.infrastructure.jpa.adapter

import org.springframework.stereotype.Repository
import org.studieojavry.iamapi.auth.application.port.SocialIdentityRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.SocialIdentity
import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider
import org.studieojavry.iamapi.auth.infrastructure.jpa.SocialIdentityJpaRepository
import org.studieojavry.iamapi.auth.infrastructure.jpa.entity.SocialIdentityEntity

@Repository
class SocialIdentityRepositoryAdapter(
    private val jpa: SocialIdentityJpaRepository
) : SocialIdentityRepositoryPort {

    override fun findByProviderAndProviderUserId(provider: SocialProvider, providerUserId: String): SocialIdentity? =
        jpa.findByProviderAndProviderUserId(provider, providerUserId)?.toDomain()

    override fun save(identity: SocialIdentity): SocialIdentity =
        jpa.save(SocialIdentityEntity.Companion.fromDomain(identity)).toDomain()

    override fun deleteByUserId(userId: Long) {
        jpa.deleteByUserId(userId)
    }

    override fun findAllByUserId(userId: Long): List<SocialIdentity> =
        jpa.findAllByUserIdOrderByLinkedAtAsc(userId).map { it.toDomain() }

    override fun countByUserId(userId: Long): Long =
        jpa.countByUserId(userId)

    override fun existsByUserIdAndProvider(userId: Long, provider: SocialProvider): Boolean =
        jpa.existsByUserIdAndProvider(userId, provider)

    override fun deleteByUserIdAndProvider(userId: Long, provider: SocialProvider): Int =
        jpa.deleteByUserIdAndProvider(userId, provider)
}
