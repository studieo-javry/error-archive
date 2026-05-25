package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.SocialIdentityRepositoryPort
import java.time.Instant

/**
 * 내 계정에 연결된 OAuth identity 목록.
 * Settings → Account → "Login & connections" 화면.
 * `providerUserId` 는 노출 안 함(provider 내부 식별자라 사용자에게 의미 X · 잠재적 추적 정보).
 */
@Service
class ListMyConnectionsUseCase(
    private val socialIdentities: SocialIdentityRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long): List<Connection> =
        socialIdentities.findAllByUserId(userId).map { si ->
            Connection(
                provider = si.provider.name,
                providerEmail = si.providerEmail,
                profileUrl = si.profileUrl,
                linkedAt = si.linkedAt,
            )
        }

    data class Connection(
        val provider: String,
        val providerEmail: String?,
        val profileUrl: String?,
        val linkedAt: Instant,
    )
}
