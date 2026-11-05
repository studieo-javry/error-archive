package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubConnection
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import java.time.Instant

/**
 * 연결의 "사용 선택" 레포 변경(replace-set) — 선택을 빼면 그 공간에선 즉시 반영이 멈춘다(syncStopped).
 *
 * grant 여부는 **로컬에 저장된 활성 레포**(마지막 sync 기준)로 검증한다 — 단순 편집에 GitHub 재호출을 하지 않는다.
 * 권한은 연결의 공간 기준([requireSpaceAccess]). 비활성(DISCONNECTED) 연결은 편집 대상이 아니다(404).
 */
@Component
@ConditionalOnGithubEnabled
class UpdateConnectionRepositoriesUseCase(
    private val connections: GithubConnectionRepositoryPort,
    private val repository: GithubInstallationRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    data class Command(val userId: Long, val connectionId: Long, val selectedRepoIds: List<Long>)
    data class Result(
        val installation: GithubInstallation,
        val connection: GithubConnection,
        val selectedRepositories: List<GithubRepository>,
        val availableRepositories: List<GithubRepository>,
    )

    fun invoke(command: Command): Result {
        val connection = connections.findByConnectionId(command.connectionId)
            ?.takeIf { it.isActive }
            ?: throw GithubConnectionNotFoundException("연결을 찾을 수 없습니다: ${command.connectionId}")
        requireSpaceAccess(workspaceQuery, command.userId, connection.space)

        val available = repository.findRepositories(listOf(connection.installationId))
            .filter { it.isActive }
            .sortedBy { it.fullName.lowercase() }
        val grantIds = available.map { it.repoId }.toSet()
        val selectedIds = command.selectedRepoIds.toSet()
        val notGranted = selectedIds - grantIds
        if (notGranted.isNotEmpty()) {
            throw GithubRepositorySelectionInvalidException("설치에 포함되지 않은 레포를 선택했습니다: $notGranted")
        }
        val installation = repository.findById(connection.installationId)
            ?: throw GithubConnectionNotFoundException("설치를 찾을 수 없습니다: ${connection.installationId}")

        return tx.execute {
            connections.replaceSelectedRepos(command.connectionId, selectedIds, Instant.now())
            Result(installation, connection, available.filter { it.repoId in selectedIds }, available)
        }!!
    }
}
