package org.studieojavry.iamapi.agentconnection.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.studieojavry.iamapi.agentconnection.domain.model.AgentConnection
import org.studieojavry.iamapi.agentconnection.domain.model.vo.AgentScope
import java.time.Instant
import java.util.UUID

/**
 * `iam_agent_connection` — agent 연결 토큰. 컬럼/제약은 V4 마이그레이션과 정확히 일치해야 한다
 * (stg/prod 는 ddl-auto=validate). scopes 는 CSV 단일 컬럼(소수 고정 집합이라 별도 테이블 불필요).
 */
@Entity
@Table(
    name = "iam_agent_connection",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_iam_agent_connection_token_hash", columnNames = ["token_hash"]),
        UniqueConstraint(name = "uq_iam_agent_connection_connection_id", columnNames = ["connection_id"]),
    ],
    indexes = [
        Index(name = "ix_iam_agent_connection_owner", columnList = "owner_user_id"),
    ],
)
class AgentConnectionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "owner_user_id", nullable = false)
    var ownerUserId: Long,

    @Column(name = "connection_id", nullable = false)
    var connectionId: UUID,

    @Column(name = "token_hash", nullable = false, length = 128)
    var tokenHash: String,

    @Column(name = "scopes", nullable = false, length = 255)
    var scopes: String,

    @Column(name = "label", length = 120)
    var label: String? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,

    @Column(name = "client_kind", length = 40)
    var clientKind: String? = null,

    @Column(name = "client_version", length = 40)
    var clientVersion: String? = null,

    @Column(name = "install_id", length = 64)
    var installId: String? = null,

    @Column(name = "install_label", length = 120)
    var installLabel: String? = null,

    @Column(name = "client_bound_at")
    var clientBoundAt: Instant? = null,
) {
    fun toDomain(): AgentConnection = AgentConnection.rehydrate(
        id = id!!,
        ownerUserId = ownerUserId,
        connectionId = connectionId,
        tokenHash = tokenHash,
        scopes = AgentScope.parseCsv(scopes),
        label = label,
        expiresAt = expiresAt,
        createdAt = createdAt,
        revokedAt = revokedAt,
        lastUsedAt = lastUsedAt,
        clientKind = clientKind,
        clientVersion = clientVersion,
        installId = installId,
        installLabel = installLabel,
        clientBoundAt = clientBoundAt,
    )

    companion object {
        fun fromDomain(c: AgentConnection): AgentConnectionEntity = AgentConnectionEntity(
            id = c.id,
            ownerUserId = c.ownerUserId,
            connectionId = c.connectionId,
            tokenHash = c.tokenHash,
            scopes = AgentScope.toCsv(c.scopes),
            label = c.label,
            expiresAt = c.expiresAt,
            createdAt = c.createdAt,
            revokedAt = c.revokedAt,
            lastUsedAt = c.lastUsedAt,
            clientKind = c.clientKind,
            clientVersion = c.clientVersion,
            installId = c.installId,
            installLabel = c.installLabel,
            clientBoundAt = c.clientBoundAt,
        )
    }
}
