package org.studieojavry.iamapi.auth.domain.model

import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider
import java.time.Instant

class SocialIdentity private constructor(
    val id: Long?,
    val userId: Long,
    val provider: SocialProvider,
    val providerUserId: String,
    val providerEmail: String?,
    val profileUrl: String?,
    val linkedAt: Instant
) {
    init {
        require(providerUserId.isNotBlank()) { "providerUserId must not be blank" }
    }

    companion object {
        fun link(
            userId: Long,
            provider: SocialProvider,
            providerUserId: String,
            providerEmail: String?,
            profileUrl: String?
        ): SocialIdentity = SocialIdentity(
            id = null,
            userId = userId,
            provider = provider,
            providerUserId = providerUserId,
            providerEmail = providerEmail,
            profileUrl = profileUrl,
            linkedAt = Instant.now()
        )

        fun rehydrate(
            id: Long,
            userId: Long,
            provider: SocialProvider,
            providerUserId: String,
            providerEmail: String?,
            profileUrl: String?,
            linkedAt: Instant
        ): SocialIdentity = SocialIdentity(id, userId, provider, providerUserId, providerEmail, profileUrl, linkedAt)
    }
}