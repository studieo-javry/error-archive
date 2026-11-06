package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubUserAuthorizationPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubConnection
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import java.time.Instant

/**
 * 연결의 "사용 선택" 레포 변경(replace-set) — 선택을 빼면 그 공간에선 즉시 반영이 멈춘다(syncStopped).
 *
 * grant 여부는 **로컬에 저장된 활성 레포**(마지막 sync 기준)로 검증한다 — 단순 편집에 GitHub 재호출을 하지 않는다.
 * 권한은 연결의 공간 기준([requireSpaceAccess]). 비활성(DISCONNECTED) 연결은 편집 대상이 아니다(404).
 *
 * **워크스페이스에 새 레포 공유 재검증(§4-D)**: 워크스페이스 연결에 **새로 추가**하는 레포는 그 사용자가 지금도 GitHub
 * repo-admin 인지 다시 확인한다(연결 후 권한이 바뀌었을 수 있으므로). 저장된 사용자 토큰으로 조회하며, 이때만 GitHub 를
 * 부른다(레포 제거·개인 편집은 로컬만). 토큰이 없거나 만료면 [GithubReauthorizationRequiredException] 으로 재연결 유도.
 */
@Component
@ConditionalOnGithubEnabled
class UpdateConnectionRepositoriesUseCase(
    private val connections: GithubConnectionRepositoryPort,
    private val repository: GithubInstallationRepositoryPort,
    private val userAuthorizations: GithubUserAuthorizationPort,
    private val githubApi: GithubAppApiPort,
    private val workspaceQuery: WorkspaceQueryPort,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    data class Command(
        val userId: Long,
        val connectionId: Long,
        val selectedRepoIds: List<Long>,
        /** 클라이언트가 마지막으로 본 연결 버전. 지정 시 현재 버전과 다르면 409(다른 관리자가 먼저 변경). */
        val expectedVersion: Long? = null,
    )
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
        if (command.expectedVersion != null && command.expectedVersion != connection.version) {
            throw GithubConnectionVersionConflictException(
                "다른 관리자가 먼저 변경했습니다 — 새로고침 후 다시 시도하세요 (expected=${command.expectedVersion}, current=${connection.version})",
            )
        }

        val available = repository.findRepositories(listOf(connection.installationId))
            .filter { it.isActive }
            .sortedBy { it.fullName.lowercase() }
        val grantIds = available.map { it.repoId }.toSet()
        val selectedIds = command.selectedRepoIds.toSet()
        val notGranted = selectedIds - grantIds
        if (notGranted.isNotEmpty()) {
            throw GithubRepositorySelectionInvalidException("설치에 포함되지 않은 레포를 선택했습니다: $notGranted")
        }
        // §4-D: 워크스페이스에 **새로 추가**하는 레포는 지금도 사용자가 repo-admin 인지 재확인(권한은 연결 후 바뀔 수 있음).
        if (connection.space is GithubSpace.Workspace) {
            val currentlySelected = connections.selectedRepoIds(command.connectionId).toSet() // ACTIVE 만
            val newlyAdded = selectedIds - currentlySelected
            if (newlyAdded.isNotEmpty()) {
                requireRepoAdmin(command.userId, connection.installationId, newlyAdded)
            }
        }
        val installation = repository.findById(connection.installationId)
            ?: throw GithubConnectionNotFoundException("설치를 찾을 수 없습니다: ${connection.installationId}")

        return tx.execute {
            connections.replaceSelectedRepos(command.connectionId, selectedIds, Instant.now())
            // 연결을 dirty 로 만들어 저장 → @Version 증가. 동시 편집은 커밋 시 낙관적 락 충돌(→ 409).
            connection.markRepositoriesChanged()
            connections.save(connection)
            // @Version 증가분은 flush/commit 시점에 반영되므로, 응답 버전은 결정적으로 +1 로 돌려준다.
            val bumped = GithubConnection.reconstitute(
                connectionId = command.connectionId,
                installationId = connection.installationId,
                space = connection.space,
                connectedByUserId = connection.connectedByUserId,
                status = connection.status,
                createdAt = connection.createdAt,
                updatedAt = connection.updatedAt,
                version = connection.version + 1,
            )
            Result(installation, bumped, available.filter { it.repoId in selectedIds }, available)
        }!!
    }

    /** 새로 추가하는 워크스페이스 레포들에 대해 사용자 GitHub repo-admin 을 저장 토큰으로 재확인. tx 밖에서 호출. */
    private fun requireRepoAdmin(userId: Long, installationId: Long, newlyAdded: Set<Long>) {
        val stored = userAuthorizations.findByEaUserId(userId)
            ?: throw GithubReauthorizationRequiredException(
                "워크스페이스에 새 레포를 공유하려면 레포 admin 권한 확인이 필요합니다 — GitHub 연결을 다시 해주세요",
            )
        val adminIds = try {
            githubApi.listUserInstallationRepositories(stored.token, installationId).filter { it.canAdmin }.map { it.id }.toSet()
        } catch (e: GithubApiException) {
            if (e.clientError) {
                userAuthorizations.deleteByGithubUserId(stored.githubUserId)
                throw GithubReauthorizationRequiredException("GitHub 인가가 만료됐습니다 — 연결을 다시 해주세요")
            }
            throw e // 5xx/한도초과는 advice 가 503/429
        }
        val notAdmin = newlyAdded - adminIds
        if (notAdmin.isNotEmpty()) {
            throw GithubRepositorySelectionInvalidException("워크스페이스에 공유하려면 레포 admin 권한이 필요합니다: $notAdmin")
        }
    }
}
