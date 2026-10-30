package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/** 내가 연결했거나 내 워크스페이스에 연결된 설치 + 활성 레포. 삭제된 설치는 숨김. */
@Component
@ConditionalOnGithubEnabled
class ListMyGithubInstallationsUseCase(
    private val repository: GithubInstallationRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    data class Item(val installation: GithubInstallation, val repositories: List<GithubRepository>)

    fun invoke(userId: Long): List<Item> {
        val workspaceIds = workspaceQuery.getAvailableWorkspaces(userId).map { it.workpaceId }
        val installations = repository.findVisibleTo(userId, workspaceIds)
            .filter { it.status != GithubInstallationStatus.DELETED }
        val reposByInstallation = repository.findRepositories(installations.map { it.installationId })
            .filter { it.isActive }
            .groupBy { it.installationId }
        return installations.map { Item(it, reposByInstallation[it.installationId].orEmpty()) }
    }
}
