package org.studieojavry.iamapi.auth.application.port

import org.studieojavry.iamapi.auth.domain.model.RefreshToken
import java.time.Instant
import java.util.UUID

interface RefreshTokenRepositoryPort {
    fun save(token: RefreshToken): RefreshToken
    fun findByTokenHash(tokenHash: String): RefreshToken?
    fun revokeFamily(familyId: UUID)
    fun revokeAllByUserId(userId: Long)

    /**
     * 활성 세션 목록 — `family` 단위로 중복 제거된 *가장 최신* row 만.
     * 각 row: revoked_at IS NULL AND expires_at > now. 정렬: lastUsedAt DESC NULLS LAST, createdAt DESC.
     */
    fun findActiveSessionsByUserId(userId: Long, now: Instant = Instant.now()): List<RefreshToken>

    /**
     * 본인 소유 family 만 revoke. 다른 user 의 family 면 0 반환(가드).
     * 반환: revoke 된 row 수.
     */
    fun revokeFamilyIfOwner(familyId: UUID, userId: Long): Int
}