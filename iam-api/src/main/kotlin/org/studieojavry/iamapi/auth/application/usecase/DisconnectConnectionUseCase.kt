package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.SocialIdentityRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider

/**
 * 단건 OAuth 연결 해제 (Settings → Connections → Disconnect).
 *
 * 가드:
 *  - 사용자에게 *남은 OAuth 연결이 1개뿐* 이면 거부(`LastConnectionRemainingException` → 409).
 *    → 로그인 수단을 모두 끊어 계정 락아웃이 발생하는 사고 방지.
 *  - provider 가 본인 카탈로그에 없으면 `ConnectionNotFoundException` → 404.
 *
 * 향후 비밀번호 로그인 도입 시: 비밀번호가 있으면 마지막 connection 도 해제 가능하도록 분기.
 */
@Service
class DisconnectConnectionUseCase(
    private val socialIdentities: SocialIdentityRepositoryPort,
) {
    @Transactional
    fun invoke(userId: Long, providerRaw: String): Result {
        val provider = parseProvider(providerRaw)

        // 1) 본인이 *가지고 있지 않은* provider → 404 (마지막 수단 가드보다 *우선*)
        if (!socialIdentities.existsByUserIdAndProvider(userId, provider)) {
            throw ConnectionNotFoundException(provider.name)
        }

        // 2) 가지고 있지만 그게 *마지막* 수단 → 409
        val current = socialIdentities.countByUserId(userId)
        if (current <= 1) {
            throw LastConnectionRemainingException(
                "must keep at least one sign-in method (current=$current)"
            )
        }

        socialIdentities.deleteByUserIdAndProvider(userId, provider)
        return Result(provider = provider.name, remaining = current - 1)
    }

    private fun parseProvider(raw: String): SocialProvider =
        runCatching { SocialProvider.valueOf(raw.uppercase()) }
            .getOrElse { throw ConnectionNotFoundException(raw) }

    data class Result(val provider: String, val remaining: Long)
}

/** 마지막 로그인 수단 — 409 conflict 로 매핑. */
class LastConnectionRemainingException(message: String) : RuntimeException(message)

/** 본인 카탈로그에 해당 provider 가 없음 — 404 로 매핑. */
class ConnectionNotFoundException(val provider: String) :
    RuntimeException("connection not found: provider=$provider")
