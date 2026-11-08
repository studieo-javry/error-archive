package org.studieojavry.iamapi.agentconnection.infrastructure.jpa.adapter

import org.springframework.stereotype.Repository
import org.studieojavry.iamapi.agentconnection.application.port.AgentConnectionRepositoryPort
import org.studieojavry.iamapi.agentconnection.domain.model.AgentConnection
import org.studieojavry.iamapi.agentconnection.infrastructure.jpa.AgentConnectionJpaRepository
import org.studieojavry.iamapi.agentconnection.infrastructure.jpa.entity.AgentConnectionEntity
import java.time.Instant
import java.util.UUID

@Repository
class AgentConnectionRepositoryAdapter(
    private val jpa: AgentConnectionJpaRepository,
) : AgentConnectionRepositoryPort {

    override fun save(connection: AgentConnection): AgentConnection =
        jpa.save(AgentConnectionEntity.fromDomain(connection)).toDomain()

    override fun findByTokenHash(tokenHash: String): AgentConnection? =
        jpa.findByTokenHash(tokenHash)?.toDomain()

    override fun listNotRevokedByOwner(ownerUserId: Long): List<AgentConnection> =
        jpa.findByOwnerUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(ownerUserId).map { it.toDomain() }

    override fun revokeByConnectionIdIfOwner(connectionId: UUID, ownerUserId: Long, now: Instant): Int =
        jpa.revokeByConnectionIdIfOwner(connectionId, ownerUserId, now)

    override fun touchLastUsed(connectionId: UUID, at: Instant) {
        jpa.touchLastUsed(connectionId, at)
    }
}
