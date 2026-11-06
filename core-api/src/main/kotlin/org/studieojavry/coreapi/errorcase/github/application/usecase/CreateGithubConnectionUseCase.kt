package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubConnection
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import java.time.Instant

/**
 * 연결 생성 — [GetGithubConnectionCandidatesUseCase] 가 준 candidateToken + 사용자가 고른 레포로
 * **설치를 공간에 연결**한다. 이게 버그(installations/new Save 비활성 막다른 길)의 실제 수정 — 설치 화면을
 * 거치지 않고 이미 접근 가능한 설치를 공간에 붙인다.
 *
 * - 소유는 candidateToken 서명이 보증(후보 조회 때 OAuth 로 확인됨) → **code 재교환 없음**.
 * - 권한: 개인 공간은 본인, 워크스페이스는 ADMIN([requireSpaceAccess]).
 * - 레포 공유: 고른 레포는 토큰에 서명된 **eligible 집합**(후보 조회 때 사용자 권한으로 판정: 개인=read/워크스페이스=admin)의
 *   부분집합이어야 하고, 현재 설치 grant 에도 남아 있어야 함(조회 후 grant 축소 방어). 아니면 400.
 * - 같은 공간에 이미 ACTIVE 연결이 있으면 409(멀티-공간이라 다른 공간 연결은 무관).
 *
 * GitHub 호출(설치/레포 조회)은 트랜잭션 밖, 저장만 짧은 트랜잭션으로.
 */
@Component
@ConditionalOnGithubEnabled
class CreateGithubConnectionUseCase(
    private val selectionCodec: GithubConnectionSelectionCodec,
    private val githubApi: GithubAppApiPort,
    private val installationSyncer: GithubInstallationSyncer,
    private val connections: GithubConnectionRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    data class Command(val userId: Long, val candidateToken: String, val selectedRepoIds: List<Long>)
    data class Result(
        val installation: GithubInstallation,
        val connection: GithubConnection,
        /** 이 연결에서 선택한 레포. */
        val selectedRepositories: List<GithubRepository>,
        /** 설치에 grant 된(선택 가능한) 활성 레포 전체. */
        val availableRepositories: List<GithubRepository>,
    )

    fun invoke(command: Command): Result {
        val selection = selectionCodec.verify(command.candidateToken)
        if (selection.userId != command.userId) {
            throw GithubConnectionCandidateInvalidException("다른 사용자의 후보 토큰입니다 — 연결을 다시 시작하세요")
        }
        val space: GithubSpace = selection.workspaceId?.let(GithubSpace::Workspace) ?: GithubSpace.Personal(command.userId)
        requireSpaceAccess(workspaceQuery, command.userId, space)

        val info = githubApi.getInstallation(selection.installationId)
        val grant = githubApi.listInstallationRepositories(selection.installationId)
        val grantIds = grant.map { it.id }.toSet()
        val eligibleIds = selection.eligibleRepoIds.toSet()
        val selectedIds = command.selectedRepoIds.toSet()
        // 공간 권한 밖(개인=read/워크스페이스=admin 미충족) 레포 — 후보 조회 때 서명된 eligible 집합으로 판정.
        val notEligible = selectedIds - eligibleIds
        if (notEligible.isNotEmpty()) {
            throw GithubRepositorySelectionInvalidException("이 공간에서 선택할 수 없는 레포입니다(접근·공유 권한): $notEligible")
        }
        // 후보 조회 후 설치 grant 가 축소됐을 수 있어 현재 grant 로도 재확인.
        val notGranted = selectedIds - grantIds
        if (notGranted.isNotEmpty()) {
            throw GithubRepositorySelectionInvalidException("설치에 포함되지 않은 레포를 선택했습니다: $notGranted")
        }

        return tx.execute {
            val installation = installationSyncer.upsertInstallation(command.userId, info)
            val synced = installationSyncer.syncRepositories(installation.installationId, grant)
            if (connections.findActive(installation.installationId, space) != null) {
                throw GithubInstallationAlreadyBoundException("이미 이 공간에 연결된 설치입니다: ${installation.installationId}")
            }
            val existing = connections.findAnyForSpaceAndInstallation(installation.installationId, space)
            val connection = connections.save(
                existing?.also { it.reactivate() } ?: GithubConnection.create(installation.installationId, space, command.userId),
            )
            connections.replaceSelectedRepos(requireNotNull(connection.connectionId), selectedIds, Instant.now())
            val available = synced.filter { it.isActive }
            val selectedRepos = available.filter { it.repoId in selectedIds }
            Result(installation, connection, selectedRepos, available)
        }!!.also {
            log.info {
                "github connection created: installation=${selection.installationId} account=${info.accountLogin} " +
                    "user=${command.userId} workspace=${selection.workspaceId} selectedRepos=${selectedIds.size}"
            }
        }
    }
}
