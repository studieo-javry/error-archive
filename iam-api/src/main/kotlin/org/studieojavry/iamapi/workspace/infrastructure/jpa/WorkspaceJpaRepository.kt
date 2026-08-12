package org.studieojavry.iamapi.workspace.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface WorkspaceJpaRepository : JpaRepository<WorkspaceEntity, Long> {
    fun existsBySlug(slug: String): Boolean
}
