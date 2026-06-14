package org.studieojavry.iamapi.auth.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Refresh token row.
 *
 * 한 *family* (UUID) = 한 디바이스의 *세션*. rotation 시 같은 family 안에 새 row 추가, 이전 row 는
 * `revoke(replacedByHash=newHash)` 로 무효화.
 *
 * 디바이스 메타(deviceLabel/userAgent/ipAddress) 는 발급 시점 컨텍스트를 보존. rotation 으로 새 row 가
 * 추가될 때 갱신해도 되고 처음 값 유지해도 됨. `lastUsedAt` 은 refresh 마다 갱신 — Settings 의 "last active" 표시용.
 */
class RefreshToken private constructor(
    val id: Long?,
    val userId: Long,
    val familyId: UUID,
    val tokenHash: String,
    val rememberMe: Boolean,
    val expiresAt: Instant,
    val createdAt: Instant,
    var revokedAt: Instant?,
    var replacedByHash: String?,
    val deviceLabel: String?,
    val userAgent: String?,
    val ipAddress: String?,
    var lastUsedAt: Instant?,
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

    fun touchLastUsed(at: Instant = Instant.now()) {
        this.lastUsedAt = at
    }

    companion object {
        fun issue(
            userId: Long,
            familyId: UUID,
            tokenHash: String,
            rememberMe: Boolean,
            expiresAt: Instant,
            deviceLabel: String? = null,
            userAgent: String? = null,
            ipAddress: String? = null,
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
                replacedByHash = null,
                deviceLabel = deviceLabel,
                userAgent = userAgent,
                ipAddress = ipAddress,
                lastUsedAt = null,
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
            replacedByHash: String?,
            deviceLabel: String?,
            userAgent: String?,
            ipAddress: String?,
            lastUsedAt: Instant?,
        ): RefreshToken = RefreshToken(
            id, userId, familyId, tokenHash, rememberMe, expiresAt, createdAt,
            revokedAt, replacedByHash, deviceLabel, userAgent, ipAddress, lastUsedAt,
        )
    }
}
