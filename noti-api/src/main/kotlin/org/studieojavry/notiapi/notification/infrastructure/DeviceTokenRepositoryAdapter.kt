package org.studieojavry.notiapi.notification.infrastructure

import org.springframework.stereotype.Repository
import org.studieojavry.notiapi.notification.application.DeviceTokenRepositoryPort
import org.studieojavry.notiapi.notification.domain.DeviceToken

@Repository
class DeviceTokenRepositoryAdapter(
    private val jpa: DeviceTokenJpaRepository,
) : DeviceTokenRepositoryPort {

    override fun register(userId: Long, token: String, platform: DeviceToken.Platform): DeviceToken {
        val existing = jpa.findByUserIdAndToken(userId, token)
        if (existing != null) return existing.toDomain()
        val saved = jpa.save(DeviceTokenEntity.fromDomain(DeviceToken.create(userId, token, platform)))
        return saved.toDomain()
    }

    override fun listByUserId(userId: Long): List<DeviceToken> =
        jpa.findAllByUserId(userId).map { it.toDomain() }

    override fun remove(userId: Long, token: String): Int =
        jpa.deleteByUserIdAndToken(userId, token)
}