package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository

/**
 * 설치 저장소 새로고침 — GitHub Configure 에서 접근 범위를 바꾸고 돌아온 뒤, App/installation 토큰으로 설치 grant 를
 * 다시 동기화한다. **새 레포는 후보로만** 반영하고(어느 공간의 선택에도 자동 추가하지 않음, §4-D·§6),
 * grant 에서 빠진 레포는 비활성 처리한다. 공간별 선택(github_connection_repository)은 건드리지 않는다.
 *
 * 권한: 이 설치에 접근 권한이 있는 ACTIVE 연결(개인=본인 / 워크스페이스=ADMIN)이 하나라도 있어야 한다. 없으면 404 로 가린다.
 * GitHub 호출(느림)은 트랜잭션 밖, 저장만 짧은 트랜잭션으로.
 */
@Component
@ConditionalOnGithubEnabled
class RefreshInstallationRepositoriesUseCase(
    private val githubApi: GithubAppApiPort,
    private val installationSyncer: GithubInstallationSyncer,
    private val repository: GithubInstallationRepositoryPort,
    private val connections: GithubConnectionRepositoryPort,
    private val workspaceQuery: org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    data class Result(
        val installation: GithubInstallation,
        /** 새로고침 후 설치에 grant 된(선택 가능한) 활성 레포 전체. */
        val availableRepositories: List<GithubRepository>,
        /** 이번에 새로 grant 된 레포 id(후보로만 — 선택되지 않음). */
        val addedRepoIds: List<Long>,
        /** 이번에 grant 에서 빠진 레포 id. */
        val removedRepoIds: List<Long>,
    )

    fun invoke(userId: Long, installationId: Long): Result {
        val authorized = connections.findActiveByInstallation(installationId)
            .any { canManageSpace(workspaceQuery, userId, it.space) }
        if (!authorized) {
            throw GithubConnectionNotFoundException("연결을 찾을 수 없습니다: installation=$installationId")
        }
        val installation = repository.findById(installationId)
            ?: throw GithubConnectionNotFoundException("설치를 찾을 수 없습니다: $installationId")
        val before = repository.findRepositories(listOf(installationId)).filter { it.isActive }.map { it.repoId }.toSet()

        val grant = githubApi.listInstallationRepositories(installationId)
        return tx.execute {
            val synced = installationSyncer.syncRepositories(installationId, grant)
            val available = synced.filter { it.isActive }.sortedBy { it.fullName.lowercase() }
            val afterIds = available.map { it.repoId }.toSet()
            Result(
                installation = installation,
                availableRepositories = available,
                addedRepoIds = (afterIds - before).toList(),
                removedRepoIds = (before - afterIds).toList(),
            )
        }!!.also {
            log.info {
                "github installation repositories refreshed: installation=$installationId user=$userId " +
                    "added=${it.addedRepoIds.size} removed=${it.removedRepoIds.size}"
            }
        }
    }
}
