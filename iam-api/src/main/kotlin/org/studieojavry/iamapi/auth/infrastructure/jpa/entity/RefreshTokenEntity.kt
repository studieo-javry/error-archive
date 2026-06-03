package org.studieojavry.iamapi.auth.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.studieojavry.iamapi.auth.domain.model.RefreshToken
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "iam_refresh_token",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_iam_refresh_token_hash", columnNames = ["token_hash"])
    ],
    indexes = [
        Index(name = "ix_iam_refresh_token_user", columnList = "user_id"),
        Index(name = "ix_iam_refresh_token_family", columnList = "family_id")
    ]
)
class RefreshTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "family_id", nullable = false)
    var familyId: UUID,

    @Column(name = "token_hash", nullable = false, length = 128)
    var tokenHash: String,

    @Column(name = "remember_me", nullable = false)
    var rememberMe: Boolean,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "revoked_at")
    var revokedAt: Instant?,

    @Column(name = "replaced_by_hash", length = 128)
    var replacedByHash: String?
) {

    fun toDomain(): RefreshToken = RefreshToken.Companion.rehydrate(
        id = id!!,
        userId = userId,
        familyId = familyId,
        tokenHash = tokenHash,
        rememberMe = rememberMe,
        expiresAt = expiresAt,
        createdAt = createdAt,
        revokedAt = revokedAt,
        replacedByHash = replacedByHash
    )

    companion object {
        fun fromDomain(token: RefreshToken): RefreshTokenEntity = RefreshTokenEntity(
            id = token.id,
            userId = token.userId,
            familyId = token.familyId,
            tokenHash = token.tokenHash,
            rememberMe = token.rememberMe,
            expiresAt = token.expiresAt,
            createdAt = token.createdAt,
            revokedAt = token.revokedAt,
            replacedByHash = token.replacedByHash
        )
    }
}
