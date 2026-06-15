package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.RefreshTokenRepositoryPort
import org.studieojavry.iamapi.shared.util.HashUtils

@Service
class LogoutUseCase(
    private val refreshTokenRepository: RefreshTokenRepositoryPort
) {

    @Transactional
    fun invoke(refreshToken: String?, userId: Long?) {
        refreshToken?.let {
            val stored = refreshTokenRepository.findByTokenHash(HashUtils.sha256(it)) ?: return@let
            refreshTokenRepository.revokeFamily(stored.familyId)
        }
        userId?.let { refreshTokenRepository.revokeAllByUserId(it) }
    }
}