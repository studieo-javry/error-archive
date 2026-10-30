package org.studieojavry.coreapi.errorcase.github.application.port

import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository

interface GithubInstallationRepositoryPort {
    fun findById(installationId: Long): GithubInstallation?
    fun save(installation: GithubInstallation): GithubInstallation

    /** 사용자가 직접 연결했거나, 사용자가 속한 워크스페이스에 연결된 설치. */
    fun findVisibleTo(userId: Long, workspaceIds: Collection<Long>): List<GithubInstallation>

    fun findRepository(repoId: Long): GithubRepository?
    fun findRepositories(installationIds: Collection<Long>): List<GithubRepository>
    fun saveRepositories(repositories: Collection<GithubRepository>)
}
