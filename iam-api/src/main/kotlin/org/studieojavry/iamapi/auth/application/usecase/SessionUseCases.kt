package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.RefreshTokenRepositoryPort
import java.time.Instant
import java.util.UUID

/**
 * Settings → Security → Sessions.
 *
 * "한 세션" = "한 refresh token family" — rotation 으로 row 가 갱신돼도 같은 family 면 한 디바이스 세션.
 * 응답의 sessionId 는 family UUID. 사용자가 그 UUID 를 직접 보진 않지만 revoke 호출에 사용.
 */
@Service
class ListMySessionsUseCase(
    private val refreshTokens: RefreshTokenRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long): List<Item> {
        val now = Instant.now()
        return refreshTokens.findActiveSessionsByUserId(userId, now).map {
            Item(
                sessionId = it.familyId,
                deviceLabel = it.deviceLabel,
                userAgent = it.userAgent,
                ipAddress = it.ipAddress,
                createdAt = it.createdAt,
                lastUsedAt = it.lastUsedAt,
                expiresAt = it.expiresAt,
                rememberMe = it.rememberMe,
            )
        }
    }

    data class Item(
        val sessionId: UUID,
        val deviceLabel: String?,
        val userAgent: String?,
        val ipAddress: String?,
        val createdAt: Instant,
        val lastUsedAt: Instant?,
        val expiresAt: Instant,
        val rememberMe: Boolean,
    )
}

/**
 * 단건 세션 종료 — `DELETE /users/me/sessions/{sessionId}`.
 * 가드: 본인 소유 family 만 revoke. 다른 user 또는 unknown family → 404.
 */
@Service
class RevokeMySessionUseCase(
    private val refreshTokens: RefreshTokenRepositoryPort,
) {
    @Transactional
    fun invoke(userId: Long, sessionId: UUID) {
        val affected = refreshTokens.revokeFamilyIfOwner(sessionId, userId)
        if (affected == 0) throw SessionNotFoundException(sessionId)
    }
}

/**
 * 모든 세션 종료 — `POST /users/me/sessions/revoke-all`.
 * 현재 디바이스도 포함. 멱등(이미 다 revoke 됐어도 200).
 */
@Service
class RevokeAllMySessionsUseCase(
    private val refreshTokens: RefreshTokenRepositoryPort,
) {
    @Transactional
    fun invoke(userId: Long) {
        refreshTokens.revokeAllByUserId(userId)
    }
}

class SessionNotFoundException(val sessionId: UUID) :
    RuntimeException("session not found: $sessionId")
