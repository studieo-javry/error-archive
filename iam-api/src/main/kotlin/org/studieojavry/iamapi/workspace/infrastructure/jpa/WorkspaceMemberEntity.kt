package org.studieojavry.iamapi.workspace.infrastructure.jpa

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
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

@Entity
@Table(
    name = "iam_workspace_member",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_iam_workspace_member_ws_user",
            columnNames = ["workspace_id", "user_id"]
        )
    ],
    indexes = [
        Index(name = "ix_iam_workspace_member_user", columnList = "user_id"),
        Index(name = "ix_iam_workspace_member_workspace", columnList = "workspace_id")
    ]
)
class WorkspaceMemberEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    var workspaceId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    var role: WorkspaceRole,

    @Column(name = "joined_at", nullable = false, updatable = false)
    var joinedAt: Instant
) {

    fun toDomain(): WorkspaceMember = WorkspaceMember.rehydrate(
        id = id!!,
        workspaceId = workspaceId,
        userId = userId,
        role = role,
        joinedAt = joinedAt
    )

    companion object {
        fun fromDomain(member: WorkspaceMember): WorkspaceMemberEntity = WorkspaceMemberEntity(
            id = member.id,
            workspaceId = member.workspaceId,
            userId = member.userId,
            role = member.role,
            joinedAt = member.joinedAt
        )
    }
}