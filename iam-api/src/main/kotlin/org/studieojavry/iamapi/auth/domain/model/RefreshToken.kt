package org.studieojavry.iamapi.auth.domain.model

import java.time.Instant
import java.util.UUID

class RefreshToken private constructor(
    val id: Long?,
    val userId: Long,
    val familyId: UUID,
    val tokenHash: String,
    val rememberMe: Boolean,
    val expiresAt: Instant,
    val createdAt: Instant,
    var revokedAt: Instant?,
    var replacedByHash: String?
) {
    init {
        require(tokenHash.isNotBlank()) { "tokenHash must not be blank" }
    }

    val isRevoked: Boolean get() = revokedAt != null

    fun isExpired(now: Instant = Instant.now()): Boolean = !now.isBefore(expiresAt)

    fun isUsable(now: Instant = Instant.now()): Boolean = !isRevoked && !isExpired(now)

    fun revoke(replacedByHash: String? = null) {
        if (revokedAt != null) return
        this.revokedAt = Instant.now()
        this.replacedByHash = replacedByHash
    }

    companion object {
        fun issue(
            userId: Long,
            familyId: UUID,
            tokenHash: String,
            rememberMe: Boolean,
            expiresAt: Instant
        ): RefreshToken =
            RefreshToken(
                id = null,
                userId = userId,
                familyId = familyId,
                tokenHash = tokenHash,
                rememberMe = rememberMe,
                expiresAt = expiresAt,
                createdAt = Instant.now(),
                revokedAt = null,
                replacedByHash = null
            )

        fun rehydrate(
            id: Long,
            userId: Long,
            familyId: UUID,
            tokenHash: String,
            rememberMe: Boolean,
            expiresAt: Instant,
            createdAt: Instant,
            revokedAt: Instant?,
            replacedByHash: String?
        ): RefreshToken = RefreshToken(
            id, userId, familyId, tokenHash, rememberMe, expiresAt, createdAt, revokedAt, replacedByHash
        )
    }
}
