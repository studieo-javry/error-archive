package org.studieojavry.iamapi.workspace.application.port

import org.studieojavry.iamapi.workspace.domain.model.Workspace

interface WorkspaceRepositoryPort {
    fun save(workspace: Workspace): Workspace
    fun findById(id: Long): Workspace?
    fun existsBySlug(slug: String): Boolean

    /** slug 단건 조회 — 데모 워크스페이스 부트스트랩 멱등성. */
    fun findBySlug(slug: String): Workspace?

    fun delete(id: Long)
}
