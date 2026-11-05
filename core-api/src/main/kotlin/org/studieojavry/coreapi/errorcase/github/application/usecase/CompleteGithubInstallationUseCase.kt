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
 * 설치 완료 — GitHub 설치 화면(installations/new)에서 FE 로 돌아온 뒤, **로그인한 FE 가** 호출한다.
 *
 * 왜 GitHub → 백엔드 콜백이 아니라 FE 경유인가: 콜백 URL 의 `installation_id`·`state` 는 누구나 만들 수 있다.
 * 공격자가 자기 state 를 넣은 설치 링크를 피해자에게 보내면, 피해자의 설치가 공격자 공간에 붙는다.
 * → (1) state.userId == **요청한 로그인 사용자** (2) GitHub 사용자 토큰으로 그 설치에 실제 접근 가능 — 둘 다 확인.
 *
 * 설치(github_installation) 는 GitHub 사실만 upsert 하고, state 가 가리키는 **공간에 연결(github_connection)** 을
 * 만들거나 되살린 뒤 **grant 된 레포 전부를 선택** 한다(기존 "모든 grant = 사용" 동작 보존). 같은 설치가 이미
 * 그 공간에 ACTIVE 로 연결돼 있으면 레포 선택만 최신화한다(멀티-공간이므로 다른 공간 연결은 그대로).
 *
 * GitHub 호출(느림)은 트랜잭션 밖, 저장만 짧은 트랜잭션으로.
 */
@Component
@ConditionalOnGithubEnabled
class CompleteGithubInstallationUseCase(
    private val stateCodec: GithubInstallStateCodec,
    private val githubApi: GithubAppApiPort,
    private val installationSyncer: GithubInstallationSyncer,
    private val connections: GithubConnectionRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    data class Command(val userId: Long, val installationId: Long, val code: String, val state: String)
    data class Result(
        val installation: GithubInstallation,
        val connection: GithubConnection,
        val repositories: List<GithubRepository>,
    )

    fun invoke(command: Command): Result {
        val state = stateCodec.verify(command.state)
        if (state.userId != command.userId) {
            throw GithubInstallStateInvalidException("다른 사용자가 시작한 연결입니다 — 연결을 다시 시작하세요")
        }
        // 발급 이후 권한이 바뀌었을 수 있어 완료 시점에 다시 확인.
        state.workspaceId?.let { requireWorkspaceAdmin(workspaceQuery, command.userId, it) }

        val userToken = githubApi.exchangeUserCode(command.code)
        if (command.installationId !in githubApi.listUserInstallationIds(userToken)) {
            throw GithubInstallationNotOwnedException("이 GitHub 계정으로 접근할 수 없는 설치입니다: ${command.installationId}")
        }
        val info = githubApi.getInstallation(command.installationId)
        val repos = githubApi.listInstallationRepositories(command.installationId)
        val space: GithubSpace = state.workspaceId?.let(GithubSpace::Workspace) ?: GithubSpace.Personal(command.userId)

        return tx.execute {
            val installation = installationSyncer.upsertInstallation(command.userId, info)
            val synced = installationSyncer.syncRepositories(installation.installationId, repos)
            val connection = upsertConnection(installation.installationId, space, command.userId)
            // 설치 직후엔 grant 된(활성) 레포 전부 선택 — 기존 동작 보존.
            connections.replaceSelectedRepos(
                requireNotNull(connection.connectionId),
                synced.filter { it.isActive }.map { it.repoId },
                Instant.now(),
            )
            Result(installation, connection, synced)
        }!!.also {
            log.info {
                "github installation connected: installation=${info.id} account=${info.accountLogin} " +
                    "user=${command.userId} workspace=${state.workspaceId} repos=${repos.size}"
            }
        }
    }

    private fun upsertConnection(installationId: Long, space: GithubSpace, userId: Long): GithubConnection {
        val existing = connections.findAnyForSpaceAndInstallation(installationId, space)
        val connection = existing?.also { it.reactivate() }
            ?: GithubConnection.create(installationId, space, userId)
        return connections.save(connection)
    }
}
