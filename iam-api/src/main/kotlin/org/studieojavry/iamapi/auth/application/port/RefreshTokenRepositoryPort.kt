package org.studieojavry.iamapi.auth.application.port

import org.studieojavry.iamapi.auth.domain.model.RefreshToken
import java.util.UUID

interface RefreshTokenRepositoryPort {
    fun save(token: RefreshToken): RefreshToken
    fun findByTokenHash(tokenHash: String): RefreshToken?
    fun revokeFamily(familyId: UUID)
    fun revokeAllByUserId(userId: Long)
}