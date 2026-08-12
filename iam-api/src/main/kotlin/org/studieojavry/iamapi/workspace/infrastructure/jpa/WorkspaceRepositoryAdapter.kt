package org.studieojavry.iamapi.workspace.infrastructure.jpa

import org.springframework.stereotype.Repository
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.Workspace

@Repository
class WorkspaceRepositoryAdapter(
    private val jpa: WorkspaceJpaRepository
) : WorkspaceRepositoryPort {

    override fun save(workspace: Workspace): Workspace =
        jpa.save(WorkspaceEntity.fromDomain(workspace)).toDomain()

    override fun findById(id: Long): Workspace? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun existsBySlug(slug: String): Boolean = jpa.existsBySlug(slug)

    override fun delete(id: Long) {
        jpa.deleteById(id)
    }
}
