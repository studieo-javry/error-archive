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
 * 내 공간들(개인 + 내가 속한 워크스페이스)의 ACTIVE 연결 + 각 연결이 선택한 활성 레포.
 *
 * 과거엔 "설치" 단위였지만 이제 한 설치가 여러 공간에 연결될 수 있어 **연결 1건 = 1 Item**. 삭제된 설치는 숨긴다.
 * (기존 응답 호환을 위해 DTO 는 연결의 공간을 workspaceId 로, 연결자를 installedByUserId 로 내려 준다.)
 */
@Component
@ConditionalOnGithubEnabled
class ListMyGithubInstallationsUseCase(
    private val repository: GithubInstallationRepositoryPort,
    private val connections: GithubConnectionRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    data class Item(
        val connection: GithubConnection,
        val installation: GithubInstallation,
        val repositories: List<GithubRepository>,
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
            val selectedIds = connections.selectedRepoIds(requireNotNull(connection.connectionId))
            val repos = repository.findRepositoriesByIds(selectedIds)
                .filter { it.isActive }
                .sortedBy { it.fullName.lowercase() }
            Item(connection, installation, repos)
        }
    }
}
