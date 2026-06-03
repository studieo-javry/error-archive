package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.command.RefreshAccessTokenCommand
import org.studieojavry.iamapi.auth.application.port.JwtIssuerPort
import org.studieojavry.iamapi.auth.application.port.RefreshTokenRepositoryPort
import org.studieojavry.iamapi.auth.application.port.SecureRandomPort
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.config.JwtProperties
import org.studieojavry.iamapi.auth.domain.model.RefreshToken
import org.studieojavry.iamapi.shared.util.HashUtils
import java.time.Instant

@Service
class RefreshAccessTokenUseCase(
    private val refreshTokenRepository: RefreshTokenRepositoryPort,
    private val userRepository: UserRepositoryPort,
    private val jwtIssuer: JwtIssuerPort,
    private val secureRandom: SecureRandomPort,
    private val jwtProperties: JwtProperties
) {

    @Transactional
    fun invoke(command: RefreshAccessTokenCommand): Result {
        val presentedHash = HashUtils.sha256(command.refreshToken)
        val stored = refreshTokenRepository.findByTokenHash(presentedHash)
            ?: throw InvalidRefreshTokenException("refresh token not recognized")

        if (stored.isRevoked) {
            refreshTokenRepository.revokeFamily(stored.familyId)
            throw InvalidRefreshTokenException("refresh token reuse detected")
        }

        if (stored.isExpired()) {
            throw InvalidRefreshTokenException("refresh token expired")
        }

        val user = userRepository.findById(stored.userId)
            ?: throw InvalidRefreshTokenException("user not found")
        user.ensureSignInAllowed()

        val now = Instant.now()
        val newRefreshRaw = secureRandom.generateUrlSafeToken(32)
        val newRefreshHash = HashUtils.sha256(newRefreshRaw)
        val newRefreshExpiresAt = now.plusSeconds(
            jwtProperties.refreshTtlSecondsFor(stored.rememberMe)
        )

        stored.revoke(replacedByHash = newRefreshHash)
        refreshTokenRepository.save(stored)

        refreshTokenRepository.save(
            RefreshToken.issue(
                userId = user.id!!,
                familyId = stored.familyId,
                tokenHash = newRefreshHash,
                rememberMe = stored.rememberMe,
                expiresAt = newRefreshExpiresAt
            )
        )

        val accessExpiresAt = now.plusSeconds(jwtProperties.accessTokenTtlSeconds)
        val accessToken = jwtIssuer.issueAccessToken(userId = user.id, expiresAt = accessExpiresAt)

        return Result(
            userId = user.id,
            accessToken = accessToken,
            accessTokenExpiresAt = accessExpiresAt,
            refreshToken = newRefreshRaw,
            refreshTokenExpiresAt = newRefreshExpiresAt,
            rememberMe = stored.rememberMe
        )
    }

    data class Result(
        val userId: Long,
        val accessToken: String,
        val accessTokenExpiresAt: Instant,
        val refreshToken: String,
        val refreshTokenExpiresAt: Instant,
        val rememberMe: Boolean
    )

    class InvalidRefreshTokenException(message: String) : RuntimeException(message)
}
