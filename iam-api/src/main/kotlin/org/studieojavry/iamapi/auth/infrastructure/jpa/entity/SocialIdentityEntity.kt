package org.studieojavry.iamapi.auth.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.studieojavry.iamapi.auth.domain.model.SocialIdentity
import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider
import java.time.Instant

@Entity
@Table(
    name = "iam_social_identity",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_iam_social_identity_provider_user",
            columnNames = ["provider", "provider_user_id"]
        )
    ],
    indexes = [Index(name = "ix_iam_social_identity_user", columnList = "user_id")]
)
class SocialIdentityEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    var provider: SocialProvider,

    @Column(name = "provider_user_id", nullable = false, length = 128)
    var providerUserId: String,

    @Column(name = "provider_email", length = 254)
    var providerEmail: String?,

    @Column(name = "profile_url", length = 512)
    var profileUrl: String?,

    @Column(name = "linked_at", nullable = false)
    var linkedAt: Instant
) {

    fun toDomain(): SocialIdentity = SocialIdentity.Companion.rehydrate(
        id = id!!,
        userId = userId,
        provider = provider,
        providerUserId = providerUserId,
        providerEmail = providerEmail,
        profileUrl = profileUrl,
        linkedAt = linkedAt
    )

    companion object {
        fun fromDomain(identity: SocialIdentity): SocialIdentityEntity = SocialIdentityEntity(
            id = identity.id,
            userId = identity.userId,
            provider = identity.provider,
            providerUserId = identity.providerUserId,
            providerEmail = identity.providerEmail,
            profileUrl = identity.profileUrl,
            linkedAt = identity.linkedAt
        )
    }
}
