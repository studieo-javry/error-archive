package org.studieojavry.iamapi.agentconnection.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.iamapi.agentconnection.infrastructure.jpa.entity.AgentConnectionEntity
import java.time.Instant
import java.util.UUID

interface AgentConnectionJpaRepository : JpaRepository<AgentConnectionEntity, Long> {

    fun findByTokenHash(tokenHash: String): AgentConnectionEntity?

    fun findByOwnerUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(ownerUserId: Long): List<AgentConnectionEntity>

    @Modifying(clearAutomatically = true)
    @Query(
        """
        update AgentConnectionEntity c
           set c.revokedAt = :now
         where c.connectionId = :connectionId
           and c.ownerUserId = :ownerUserId
           and c.revokedAt is null
        """
    )
    fun revokeByConnectionIdIfOwner(
        @Param("connectionId") connectionId: UUID,
        @Param("ownerUserId") ownerUserId: Long,
        @Param("now") now: Instant,
    ): Int

    @Modifying(clearAutomatically = true)
    @Query(
        """
        update AgentConnectionEntity c
           set c.lastUsedAt = :at
         where c.connectionId = :connectionId
        """
    )
    fun touchLastUsed(@Param("connectionId") connectionId: UUID, @Param("at") at: Instant): Int
}
