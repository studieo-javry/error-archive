package org.studieojavry.iamapi.workspace.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.iamapi.workspace.domain.model.Workspace
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceName
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceSettings
import java.time.Instant

@Entity
@Table(
    name = "iam_workspace",
    indexes = [Index(name = "ix_iam_workspace_creator", columnList = "created_by_user_id")]
)
class WorkspaceEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "name", nullable = false, length = 50)
    var name: String,

    @Embedded
    var settings: WorkspaceSettingsEmbeddable,

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    var createdByUserId: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
) {

    fun toDomain(): Workspace = Workspace.rehydrate(
        id = id!!,
        name = WorkspaceName(name),
        settings = WorkspaceSettings(
            notificationEnabled = settings.notificationEnabled,
            defaultTimezone = settings.defaultTimezone
        ),
        createdByUserId = createdByUserId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(workspace: Workspace): WorkspaceEntity = WorkspaceEntity(
            id = workspace.id,
            name = workspace.name.value,
            settings = WorkspaceSettingsEmbeddable(
                notificationEnabled = workspace.settings.notificationEnabled,
                defaultTimezone = workspace.settings.defaultTimezone
            ),
            createdByUserId = workspace.createdByUserId,
            createdAt = workspace.createdAt,
            updatedAt = workspace.updatedAt
        )
    }
}

@Embeddable
data class WorkspaceSettingsEmbeddable(
    @Column(name = "notification_enabled", nullable = false)
    var notificationEnabled: Boolean = true,

    @Column(name = "default_timezone", nullable = false, length = 64)
    var defaultTimezone: String = "UTC"
)