package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubInstallationJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubRepositoryJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubInstallationEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubRepositoryEntity

@Component
class GithubInstallationRepositoryAdapter(
    private val installations: GithubInstallationJpaRepository,
    private val repositories: GithubRepositoryJpaRepository,
) : GithubInstallationRepositoryPort {

    override fun findById(installationId: Long): GithubInstallation? =
        installations.findById(installationId).orElse(null)?.toDomain()

    override fun save(installation: GithubInstallation): GithubInstallation =
        installations.save(GithubInstallationEntity.from(installation)).toDomain()

    override fun findVisibleTo(userId: Long, workspaceIds: Collection<Long>): List<GithubInstallation> =
        // 빈 IN () 은 JPQL 방언별로 깨질 수 있어 더미 값으로 채운다(워크스페이스 id 는 양수).
        installations.findVisibleTo(userId, workspaceIds.ifEmpty { listOf(-1L) }).map { it.toDomain() }

    override fun findRepository(repoId: Long): GithubRepository? =
        repositories.findById(repoId).orElse(null)?.toDomain()

    override fun findActiveRepositoryByFullName(fullName: String): GithubRepository? =
        repositories.findFirstByFullNameIgnoreCaseAndRemovedAtIsNull(fullName)?.toDomain()

    override fun findRepositoriesByIds(repoIds: Collection<Long>): List<GithubRepository> =
        if (repoIds.isEmpty()) emptyList() else repositories.findAllById(repoIds).map { it.toDomain() }

    override fun findByIds(installationIds: Collection<Long>): List<GithubInstallation> =
        if (installationIds.isEmpty()) emptyList() else installations.findAllById(installationIds).map { it.toDomain() }

    override fun findRepositories(installationIds: Collection<Long>): List<GithubRepository> =
        if (installationIds.isEmpty()) emptyList()
        else repositories.findAllByInstallationIdInOrderByFullName(installationIds).map { it.toDomain() }

    override fun saveRepositories(repositories: Collection<GithubRepository>) {
        if (repositories.isEmpty()) return
        this.repositories.saveAll(repositories.map { GithubRepositoryEntity.from(it) })
    }
}
