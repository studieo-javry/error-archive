package org.studieojavry.iamapi.auth.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.iamapi.auth.infrastructure.jpa.entity.RefreshTokenEntity
import java.time.Instant
import java.util.UUID

interface RefreshTokenJpaRepository : JpaRepository<RefreshTokenEntity, Long> {

    fun findByTokenHash(tokenHash: String): RefreshTokenEntity?

    @Modifying(clearAutomatically = true)
    @Query(
        """
        update RefreshTokenEntity rt
           set rt.revokedAt = :now
         where rt.familyId = :familyId
           and rt.revokedAt is null
        """
    )
    fun revokeFamily(@Param("familyId") familyId: UUID, @Param("now") now: Instant): Int

    @Modifying(clearAutomatically = true)
    @Query(
        """
        update RefreshTokenEntity rt
           set rt.revokedAt = :now
         where rt.userId = :userId
           and rt.revokedAt is null
        """
    )
    fun revokeAllByUserId(@Param("userId") userId: Long, @Param("now") now: Instant): Int
}
