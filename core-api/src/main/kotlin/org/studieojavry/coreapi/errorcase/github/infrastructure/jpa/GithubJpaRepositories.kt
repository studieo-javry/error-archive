package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubInstallationEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubRepositoryEntity

interface GithubInstallationJpaRepository : JpaRepository<GithubInstallationEntity, Long> {

    @Query(
        """
        SELECT i FROM GithubInstallationEntity i
         WHERE i.installedByUserId = :userId
            OR (i.workspaceId IS NOT NULL AND i.workspaceId IN :workspaceIds)
         ORDER BY i.createdAt
        """,
    )
    fun findVisibleTo(
        @Param("userId") userId: Long,
        @Param("workspaceIds") workspaceIds: Collection<Long>,
    ): List<GithubInstallationEntity>
}

interface GithubRepositoryJpaRepository : JpaRepository<GithubRepositoryEntity, Long> {
    fun findAllByInstallationIdInOrderByFullName(installationIds: Collection<Long>): List<GithubRepositoryEntity>
}
