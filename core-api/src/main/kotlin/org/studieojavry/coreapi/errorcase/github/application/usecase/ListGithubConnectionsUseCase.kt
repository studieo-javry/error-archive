package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubConnection
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/**
 * 연결 관리 화면용 — 내 공간들(개인 + 내가 속한 워크스페이스)의 ACTIVE 연결과, 각 연결의 **선택 레포 + 선택 가능(grant) 레포**.
 *
 * [ListMyGithubInstallationsUseCase] 와 달리 connectionId·선택 가능 레포까지 내려, FE 가 체크박스로 선택을 편집할 수 있게 한다.
 */
@Component
@ConditionalOnGithubEnabled
class ListGithubConnectionsUseCase(
    private val connections: GithubConnectionRepositoryPort,
    private val repository: GithubInstallationRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    data class Item(
        val connection: GithubConnection,
        val installation: GithubInstallation,
        val selectedRepositories: List<GithubRepository>,
        val availableRepositories: List<GithubRepository>,
    )

    fun invoke(userId: Long): List<Item> {
        val spaces: List<GithubSpace> = buildList {
            add(GithubSpace.Personal(userId))
            workspaceQuery.getAvailableWorkspaces(userId).forEach { add(GithubSpace.Workspace(it.workpaceId)) }
        }
        val activeConnections = connections.findActiveBySpaces(spaces)
        if (activeConnections.isEmpty()) return emptyList()

        val installationById = repository.findByIds(activeConnections.map { it.installationId }.toSet())
            .filter { it.status != GithubInstallationStatus.DELETED }
            .associateBy { it.installationId }

        return activeConnections.mapNotNull { connection ->
            val installation = installationById[connection.installationId] ?: return@mapNotNull null
            val available = repository.findRepositories(listOf(connection.installationId))
                .filter { it.isActive }
                .sortedBy { it.fullName.lowercase() }
            val selectedIds = connections.selectedRepoIds(requireNotNull(connection.connectionId))
            val selected = available.filter { it.repoId in selectedIds }
            Item(connection, installation, selected, available)
        }
    }
}
