package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubConnection
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubConnectionRepositoryStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubConnectionStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import java.io.Serializable
import java.time.Instant
import java.time.LocalDateTime

/**
 * 설치 ↔ 공간 연결. 공간은 (owner_user_id, workspace_id) 둘 중 정확히 하나로 표현(DB CHECK 로 강제).
 * ACTIVE 중복은 부분 유니크 인덱스(uq_github_connection_personal/workspace)가 막는다 — 엔티티엔 선언하지 않음.
 */
@Entity
@Table(
    name = "github_connection",
    indexes = [
        Index(name = "ix_github_connection_installation", columnList = "installation_id"),
        Index(name = "ix_github_connection_owner", columnList = "owner_user_id"),
        Index(name = "ix_github_connection_workspace", columnList = "workspace_id"),
    ],
)
class GithubConnectionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "connection_id")
    var connectionId: Long?,

    @Column(name = "installation_id", nullable = false)
    var installationId: Long,

    @Column(name = "owner_user_id")
    var ownerUserId: Long?,

    @Column(name = "workspace_id")
    var workspaceId: Long?,

    @Column(name = "connected_by_user_id", nullable = false)
    var connectedByUserId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: GithubConnectionStatus,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
) {
    fun toDomain(): GithubConnection {
        val space = workspaceId?.let(GithubSpace::Workspace)
            ?: GithubSpace.Personal(requireNotNull(ownerUserId) { "connection $connectionId has neither workspace nor owner" })
        return GithubConnection.reconstitute(
            connectionId = requireNotNull(connectionId),
            installationId = installationId,
            space = space,
            connectedByUserId = connectedByUserId,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    companion object {
        fun from(d: GithubConnection): GithubConnectionEntity {
            val ownerUserId = (d.space as? GithubSpace.Personal)?.ownerUserId
            val workspaceId = (d.space as? GithubSpace.Workspace)?.workspaceId
            return GithubConnectionEntity(
                connectionId = d.connectionId,
                installationId = d.installationId,
                ownerUserId = ownerUserId,
                workspaceId = workspaceId,
                connectedByUserId = d.connectedByUserId,
                status = d.status,
                createdAt = d.createdAt,
                updatedAt = d.updatedAt,
            )
        }
    }
}

data class GithubConnectionRepositoryKey(
    var connectionId: Long = 0,
    var repoId: Long = 0,
) : Serializable

/** 연결이 "사용 선택" 한 레포. 설치 grant 레포(github_repository)의 부분집합. */
@Entity
@Table(
    name = "github_connection_repository",
    indexes = [Index(name = "ix_github_connection_repository_repo", columnList = "repo_id")],
)
@IdClass(GithubConnectionRepositoryKey::class)
class GithubConnectionRepositoryEntity(
    @Id
    @Column(name = "connection_id")
    var connectionId: Long,

    @Id
    @Column(name = "repo_id")
    var repoId: Long,

    @Column(name = "selected_at", nullable = false)
    var selectedAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: GithubConnectionRepositoryStatus = GithubConnectionRepositoryStatus.ACTIVE,
)
