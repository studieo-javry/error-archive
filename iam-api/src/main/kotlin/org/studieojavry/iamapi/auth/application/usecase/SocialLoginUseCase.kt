package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.command.SocialLoginCommand
import org.studieojavry.iamapi.auth.application.port.JwtIssuerPort
import org.studieojavry.iamapi.auth.application.port.RefreshTokenRepositoryPort
import org.studieojavry.iamapi.auth.application.port.SecureRandomPort
import org.studieojavry.iamapi.auth.application.port.SocialIdentityRepositoryPort
import org.studieojavry.iamapi.auth.application.port.SocialProfileFetcherPort
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.config.JwtProperties
import org.studieojavry.iamapi.auth.domain.model.RefreshToken
import org.studieojavry.iamapi.auth.domain.model.SocialIdentity
import org.studieojavry.iamapi.auth.domain.model.User
import org.studieojavry.iamapi.auth.domain.model.vo.Email
import org.studieojavry.iamapi.shared.util.HashUtils
import java.time.Instant
import java.util.UUID

@Service
class SocialLoginUseCase(
    private val profileFetchers: List<SocialProfileFetcherPort>,
    private val userRepository: UserRepositoryPort,
    private val socialIdentityRepository: SocialIdentityRepositoryPort,
    private val refreshTokenRepository: RefreshTokenRepositoryPort,
    private val jwtIssuer: JwtIssuerPort,
    private val secureRandom: SecureRandomPort,
    private val jwtProperties: JwtProperties
) {

    @Transactional
    fun invoke(command: SocialLoginCommand): Result {
        val fetcher = profileFetchers.firstOrNull { it.supports(command.provider) }
            ?: throw IllegalArgumentException("provider not supported: ${command.provider}")

        val profile = fetcher.fetch(command.credential)

        val identity = socialIdentityRepository
            .findByProviderAndProviderUserId(command.provider, profile.providerUserId)

        val user = if (identity != null) {
            val existing = userRepository.findById(identity.userId)
                ?: throw IllegalStateException("user not found for identity: ${identity.id}")
            existing.ensureSignInAllowed()
            if (existing.avatarUrl == null && profile.avatarUrl != null) {
                existing.changeAvatar(profile.avatarUrl)
                userRepository.save(existing)
            } else {
                existing
            }
        } else {
            val handle = pickAvailableHandle(profile.providerLogin)
            val created = userRepository.save(
                User.create(
                    email = profile.email?.let { Email(it) },
                    handle = handle,
                    displayName = profile.displayName,
                    avatarUrl = profile.avatarUrl
                )
            )
            socialIdentityRepository.save(
                SocialIdentity.link(
                    userId = created.id!!,
                    provider = command.provider,
                    providerUserId = profile.providerUserId,
                    providerEmail = profile.email,
                    profileUrl = profile.profileUrl
                )
            )
            created
        }

        val tokens = issueTokens(
            userId = user.id!!,
            familyId = UUID.randomUUID(),
            rememberMe = command.rememberMe,
            deviceLabel = command.deviceLabel,
            userAgent = command.userAgent,
            ipAddress = command.ipAddress,
        )
        return Result(
            userId = user.id,
            accessToken = tokens.accessToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
            rememberMe = command.rememberMe
        )
    }

    /**
     * provider 의 login 을 우리 handle 로 매핑. 충돌 시 `-2`, `-3` … suffix.
     *
     * 정규화:
     *  - 소문자
     *  - `[^a-z0-9-]` → 하이픈으로 치환 (이론상 GitHub login 은 이미 영문/숫자/하이픈만, 단 방어적 처리)
     *  - 시작/끝 하이픈 제거 (`-foo` → `foo`)
     *  - 빈 결과면 `user`
     *  - 39자 cap
     */
    private fun pickAvailableHandle(providerLogin: String): String {
        val base = providerLogin
            .lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .trim('-')
            .ifBlank { "user" }
            .take(User.HANDLE_MAX_LENGTH)

        if (!userRepository.existsByHandle(base)) return base

        // suffix `-2`, `-3` … 까지 시도. 너무 많이 충돌하면 매우 드문 케이스 — 마지막 fallback 으로 short uuid.
        for (i in 2..99) {
            val candidate = "${base.take(User.HANDLE_MAX_LENGTH - 3)}-$i"
            if (!userRepository.existsByHandle(candidate)) return candidate
        }
        val short = secureRandom.generateUrlSafeToken(6).lowercase().replace(Regex("[^a-z0-9]"), "").take(6)
        return "${base.take(User.HANDLE_MAX_LENGTH - 7)}-$short"
    }

    private fun issueTokens(
        userId: Long,
        familyId: UUID,
        rememberMe: Boolean,
        deviceLabel: String?,
        userAgent: String?,
        ipAddress: String?,
    ): IssuedTokens {
        val now = Instant.now()
        val accessExpiresAt = now.plusSeconds(jwtProperties.accessTokenTtlSeconds)
        val refreshExpiresAt = now.plusSeconds(jwtProperties.refreshTtlSecondsFor(rememberMe))

        val accessToken = jwtIssuer.issueAccessToken(userId = userId, expiresAt = accessExpiresAt)
        val refreshTokenRaw = secureRandom.generateUrlSafeToken(32)

        refreshTokenRepository.save(
            RefreshToken.issue(
                userId = userId,
                familyId = familyId,
                tokenHash = HashUtils.sha256(refreshTokenRaw),
                rememberMe = rememberMe,
                expiresAt = refreshExpiresAt,
                deviceLabel = deviceLabel,
                userAgent = userAgent,
                ipAddress = ipAddress,
            )
        )
        return IssuedTokens(accessToken, accessExpiresAt, refreshTokenRaw, refreshExpiresAt)
    }

    private data class IssuedTokens(
        val accessToken: String,
        val accessTokenExpiresAt: Instant,
        val refreshToken: String,
        val refreshTokenExpiresAt: Instant
    )

    data class Result(
        val userId: Long,
        val accessToken: String,
        val accessTokenExpiresAt: Instant,
        val refreshToken: String,
        val refreshTokenExpiresAt: Instant,
        val rememberMe: Boolean
    )
}
