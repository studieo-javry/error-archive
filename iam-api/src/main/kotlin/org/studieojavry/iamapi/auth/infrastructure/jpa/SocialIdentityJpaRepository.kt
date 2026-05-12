package org.studieojavry.iamapi.auth.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider
import org.studieojavry.iamapi.auth.infrastructure.jpa.entity.SocialIdentityEntity

interface SocialIdentityJpaRepository : JpaRepository<SocialIdentityEntity, Long> {
    fun findByProviderAndProviderUserId(provider: SocialProvider, providerUserId: String): SocialIdentityEntity?

    fun findAllByUserIdOrderByLinkedAtAsc(userId: Long): List<SocialIdentityEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from SocialIdentityEntity s where s.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: Long): Int

    fun countByUserId(userId: Long): Long

    fun existsByUserIdAndProvider(userId: Long, provider: SocialProvider): Boolean

    @Modifying(clearAutomatically = true)
    @Query("delete from SocialIdentityEntity s where s.userId = :userId and s.provider = :provider")
    fun deleteByUserIdAndProvider(@Param("userId") userId: Long, @Param("provider") provider: SocialProvider): Int
}
