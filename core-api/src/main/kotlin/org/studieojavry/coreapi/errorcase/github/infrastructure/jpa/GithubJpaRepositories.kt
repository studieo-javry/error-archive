package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubInstallationEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubRepositoryEntity

interface GithubInstallationJpaRepository : JpaRepository<GithubInstallationEntity, Long>

interface GithubRepositoryJpaRepository : JpaRepository<GithubRepositoryEntity, Long> {
    fun findAllByInstallationIdInOrderByFullName(installationIds: Collection<Long>): List<GithubRepositoryEntity>
    fun findFirstByFullNameIgnoreCaseAndRemovedAtIsNull(fullName: String): GithubRepositoryEntity?
}
