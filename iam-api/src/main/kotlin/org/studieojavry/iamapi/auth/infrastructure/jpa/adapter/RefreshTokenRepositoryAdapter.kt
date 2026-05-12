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

    override fun findActiveSessionsByUserId(userId: Long, now: Instant): List<RefreshToken> {
        val all = jpa.findActiveByUserId(userId, now).map { it.toDomain() }
        // family 별 *가장 최신* row 만. 같은 family 안에서 여러 row 가 활성이면(rotation 직후 등) 시간 큰 것 선호.
        return all.groupBy { it.familyId }
            .map { (_, rows) -> rows.maxBy { (it.lastUsedAt ?: it.createdAt) } }
            .sortedByDescending { it.lastUsedAt ?: it.createdAt }
    }

    override fun revokeFamilyIfOwner(familyId: UUID, userId: Long): Int =
        jpa.revokeFamilyIfOwner(familyId, userId, Instant.now())
}
