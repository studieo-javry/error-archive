package org.studieojavry.iamapi.workspace.domain.model

import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceName
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceSettings
import java.time.Instant

class Workspace private constructor(
    val id: Long?,
    var name: WorkspaceName,
    var settings: WorkspaceSettings,
    val createdByUserId: Long,
    val createdAt: Instant,
    var updatedAt: Instant
) {

    fun rename(newName: WorkspaceName) {
        if (this.name == newName) return
        this.name = newName
        touch()
    }

    fun updateSettings(newSettings: WorkspaceSettings) {
        if (this.settings == newSettings) return
        this.settings = newSettings
        touch()
    }

    private fun touch() {
        this.updatedAt = Instant.now()
    }

    companion object {
        fun create(name: WorkspaceName, createdByUserId: Long): Workspace {
            val now = Instant.now()
            return Workspace(
                id = null,
                name = name,
                settings = WorkspaceSettings.defaults(),
                createdByUserId = createdByUserId,
                createdAt = now,
                updatedAt = now
            )
        }

        fun rehydrate(
            id: Long,
            name: WorkspaceName,
            settings: WorkspaceSettings,
            createdByUserId: Long,
            createdAt: Instant,
            updatedAt: Instant
        ): Workspace = Workspace(id, name, settings, createdByUserId, createdAt, updatedAt)
    }
}
