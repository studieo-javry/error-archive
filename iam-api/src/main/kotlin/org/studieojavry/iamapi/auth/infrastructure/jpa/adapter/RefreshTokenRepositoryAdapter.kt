package org.studieojavry.iamapi.auth.infrastructure.jpa.adapter

import org.springframework.stereotype.Repository
import org.studieojavry.iamapi.auth.application.port.RefreshTokenRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.RefreshToken
import org.studieojavry.iamapi.auth.infrastructure.jpa.RefreshTokenJpaRepository
import org.studieojavry.iamapi.auth.infrastructure.jpa.entity.RefreshTokenEntity
import java.time.Instant
import java.util.UUID

@Repository
class RefreshTokenRepositoryAdapter(
    private val jpa: RefreshTokenJpaRepository
) : RefreshTokenRepositoryPort {

    override fun save(token: RefreshToken): RefreshToken =
        jpa.save(RefreshTokenEntity.Companion.fromDomain(token)).toDomain()

    override fun findByTokenHash(tokenHash: String): RefreshToken? =
        jpa.findByTokenHash(tokenHash)?.toDomain()

    override fun revokeFamily(familyId: UUID) {
        jpa.revokeFamily(familyId, Instant.now())
    }

    override fun revokeAllByUserId(userId: Long) {
        jpa.revokeAllByUserId(userId, Instant.now())
    }
}
