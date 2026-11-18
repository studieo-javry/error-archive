package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubUserAuthorizationPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubConnection
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubUserAuthorization
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import java.time.Clock
import java.time.Instant

/**
 * 설치 완료 — GitHub 설치 화면(installations/new)에서 FE 로 돌아온 뒤, **로그인한 FE 가** 호출한다.
 *
 * 왜 GitHub → 백엔드 콜백이 아니라 FE 경유인가: 콜백 URL 의 `installation_id`·`state` 는 누구나 만들 수 있다.
 * 공격자가 자기 state 를 넣은 설치 링크를 피해자에게 보내면, 피해자의 설치가 공격자 공간에 붙는다.
 * → (1) state.userId == **요청한 로그인 사용자** (2) GitHub 사용자 토큰으로 그 설치에 실제 접근 가능 — 둘 다 확인.
 *
 * 설치(github_installation) 는 GitHub 사실만 upsert 하고, state 가 가리키는 **공간에 연결(github_connection)** 을
 * 만들거나 되살린다. 레포 선택(github_connection_repository)은 **새 연결을 부트스트랩할 때만** grant 된 레포로 채운다.
 * 이미 존재하던(재활성) 연결의 공간별 선택은 **절대 덮어쓰지 않는다** — 재완료가 다른 관리자가 다듬어 둔 선택을
 * 되돌리거나 새 grant 를 자동 추가하면 안 되므로(지시서 §1). 공간별 명시 선택·변경은 candidates→connections 흐름과
 * `PUT /connections/{id}/repositories` 가 담당한다(레거시 one-shot 부트스트랩은 2차에서 명시 선택으로 대체 예정).
 *
 * Flow A(OAuth 후보)와 통일 — 설치에 쓴 **사용자 인가도 저장**(암호화)해 시나리오 ②(재이동 없는 재연결)를 Flow B 연결에도
 * 준다. refresh 토큰은 "6개월 기억" 동의(state.remember) 시에만 보관(기본 미동의=단명 토큰만=B).
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
    private val userAuthorizations: GithubUserAuthorizationPort,
    private val workspaceQuery: WorkspaceQueryPort,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemUTC(),
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
        if (command.installationId !in githubApi.listUserInstallationIds(userToken.token)) {
            throw GithubInstallationNotOwnedException("이 GitHub 계정으로 접근할 수 없는 설치입니다: ${command.installationId}")
        }
        // Flow A(후보)와 통일 — 설치 완료 사용자의 인가도 저장해 시나리오 ②(재이동 없는 재연결)를 Flow B 연결에도 제공한다.
        // refresh 토큰·만료는 "6개월 기억" 동의(state.remember) 시에만 저장(기본 미동의=단명 토큰만=B). GitHub 숫자 id 로 revoked 웹훅과 잇는다.
        val ghUser = githubApi.getAuthenticatedUser(userToken.token)
        val now = Instant.now(clock)
        userAuthorizations.save(
            GithubUserAuthorization(
                eaUserId = command.userId,
                githubUserId = ghUser.id,
                githubLogin = ghUser.login,
                token = userToken.token,
                createdAt = now,
                updatedAt = now,
                refreshToken = if (state.remember) userToken.refreshToken else null,
                expiresAt = if (state.remember) userToken.expiresAt else null,
                refreshTokenExpiresAt = if (state.remember) userToken.refreshTokenExpiresAt else null,
            ),
        )
        val info = githubApi.getInstallation(command.installationId)
        val repos = githubApi.listInstallationRepositories(command.installationId)
        val space: GithubSpace = state.workspaceId?.let(GithubSpace::Workspace) ?: GithubSpace.Personal(command.userId)

        return tx.execute {
            val installation = installationSyncer.upsertInstallation(command.userId, info)
            val synced = installationSyncer.syncRepositories(installation.installationId, repos)
            val existing = connections.findAnyForSpaceAndInstallation(installation.installationId, space)
            val connection = connections.save(
                existing?.also { it.reactivate() } ?: GithubConnection.create(installation.installationId, space, command.userId),
            )
            // 새 연결에서만 grant 된(활성) 레포를 선택해 부트스트랩한다. 재활성 연결의 기존 선택은 보존(덮어쓰기 금지, §1).
            if (existing == null) {
                connections.replaceSelectedRepos(
                    requireNotNull(connection.connectionId),
                    synced.filter { it.isActive }.map { it.repoId },
                    Instant.now(),
                )
            }
            Result(installation, connection, synced)
        }!!.also {
            log.info {
                "github installation connected: installation=${info.id} account=${info.accountLogin} " +
                    "user=${command.userId} workspace=${state.workspaceId} repos=${repos.size}"
            }
        }
    }
}
