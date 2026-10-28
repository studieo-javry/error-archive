package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/**
 * 설치 완료 — GitHub 설치 화면에서 FE 로 돌아온 뒤, **로그인한 FE 가** 호출한다.
 *
 * 왜 GitHub → 백엔드 콜백이 아니라 FE 경유인가: 콜백 URL 의 `installation_id`·`state` 는 누구나 만들 수 있다.
 * 공격자가 자기 state 를 넣은 설치 링크를 피해자에게 보내면, 피해자의 설치가 공격자 워크스페이스에 붙는다.
 * → (1) state.userId == **요청한 로그인 사용자** (2) GitHub 사용자 토큰으로 그 설치에 실제 접근 가능 — 둘 다 확인.
 *
 * GitHub 호출(느림)은 트랜잭션 밖, 저장만 짧은 트랜잭션으로.
 */
@Component
@ConditionalOnGithubEnabled
class CompleteGithubInstallationUseCase(
    private val stateCodec: GithubInstallStateCodec,
    private val githubApi: GithubAppApiPort,
    private val repository: GithubInstallationRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    data class Command(val userId: Long, val installationId: Long, val code: String, val state: String)
    data class Result(val installation: GithubInstallation, val repositories: List<GithubRepository>)

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

        return tx.execute {
            val installation = bind(command.userId, state.workspaceId, info)
            Result(installation, syncRepositories(installation.installationId, repos))
        }!!.also {
            log.info {
                "github installation connected: installation=${info.id} account=${info.accountLogin} " +
                    "user=${command.userId} workspace=${state.workspaceId} repos=${repos.size}"
            }
        }
    }

    private fun bind(userId: Long, workspaceId: Long?, info: GithubAppApiPort.InstallationInfo): GithubInstallation {
        val existing = repository.findById(info.id)
        val installation = when {
            existing == null -> GithubInstallation.connect(info.id, info.accountLogin, info.accountType, workspaceId, userId)
            existing.status == GithubInstallationStatus.DELETED || existing.isBoundTo(userId, workspaceId) ->
                existing.also { it.rebind(userId, workspaceId, info.accountLogin, info.accountType) }
            // 같은 사용자가 다른 범위로 옮기거나, 같은 워크스페이스의 다른 ADMIN 이 재연결 — 위에서 ADMIN 확인 완료.
            existing.installedByUserId == userId || (workspaceId != null && existing.workspaceId == workspaceId) ->
                existing.also { it.rebind(userId, workspaceId, info.accountLogin, info.accountType) }
            else -> throw GithubInstallationAlreadyBoundException(
                "이미 다른 사용자/워크스페이스에 연결된 GitHub 설치입니다: ${info.accountLogin}",
            )
        }
        return repository.save(installation)
    }

    /** 설치 대상 레포를 GitHub 기준으로 맞춘다 — 목록에 없는 기존 레포는 지우지 않고 removed 표시. */
    private fun syncRepositories(
        installationId: Long,
        current: List<GithubAppApiPort.RepositoryInfo>,
    ): List<GithubRepository> {
        val known = repository.findRepositories(listOf(installationId)).associateBy { it.repoId }
        val currentIds = current.map { it.id }.toSet()
        val upserts = current.map { r ->
            (known[r.id] ?: repository.findRepository(r.id))
                ?.also { it.attach(installationId, r.fullName, r.isPrivate) }
                ?: GithubRepository.of(r.id, installationId, r.fullName, r.isPrivate)
        }
        val removed = known.values.filter { it.repoId !in currentIds }.onEach { it.detach() }
        repository.saveRepositories(upserts + removed)
        return upserts
    }
}
