package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.RefreshTokenRepositoryPort
import org.studieojavry.iamapi.shared.util.HashUtils

@Service
class LogoutUseCase(
    private val refreshTokenRepository: RefreshTokenRepositoryPort
) {

    /**
     * 현재 디바이스의 family 만 revoke (전체 디바이스 sign-out 아님).
     *
     * "Sign out everywhere" 는 별도 endpoint [RevokeAllMySessionsUseCase] (POST /me/sessions/revoke-all).
     * 멱등 — refreshToken 이 null 이거나 DB 에 없거나 이미 revoked 여도 no-op.
     */
    @Transactional
    fun invoke(refreshToken: String?) {
        refreshToken?.let {
            val stored = refreshTokenRepository.findByTokenHash(HashUtils.sha256(it)) ?: return@let
            refreshTokenRepository.revokeFamily(stored.familyId)
        }
    }
}