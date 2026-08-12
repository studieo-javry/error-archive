package org.studieojavry.iamapi.workspace.domain.model

import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceName
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceSettings
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceSlug
import java.time.Instant

class Workspace private constructor(
    val id: Long?,
    var name: WorkspaceName,
    /** URL 식별자. 생성 후 변경 불가. */
    val slug: WorkspaceSlug,
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
        fun create(name: WorkspaceName, slug: WorkspaceSlug, createdByUserId: Long): Workspace {
            val now = Instant.now()
            return Workspace(
                id = null,
                name = name,
                slug = slug,
                settings = WorkspaceSettings.defaults(),
                createdByUserId = createdByUserId,
                createdAt = now,
                updatedAt = now
            )
        }

        fun rehydrate(
            id: Long,
            name: WorkspaceName,
            slug: WorkspaceSlug,
            settings: WorkspaceSettings,
            createdByUserId: Long,
            createdAt: Instant,
            updatedAt: Instant
        ): Workspace = Workspace(id, name, slug, settings, createdByUserId, createdAt, updatedAt)
    }
}
