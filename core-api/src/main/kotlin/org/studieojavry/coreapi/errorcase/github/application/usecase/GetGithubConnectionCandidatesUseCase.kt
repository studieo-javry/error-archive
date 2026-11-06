package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubUserAuthorizationPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubUserAuthorization
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import java.time.Clock
import java.time.Instant

/**
 * 연결 후보 조회 — OAuth 인가 콜백의 `code`·`state` 를 받아, 그 사용자가 **이미 접근 가능한 설치**들을
 * (설치 화면 없이) 후보로 나열한다. 설치마다 grant 된 레포와 **선택 토큰(candidateToken)** 을 함께 내려
 * FE 가 레포를 고른 뒤 [CreateGithubConnectionUseCase] 로 연결을 만들 수 있게 한다.
 *
 * OAuth `code` 는 **여기서 딱 한 번** 사용자 토큰으로 교환한다. 이후 연결 생성은 candidateToken 서명으로 소유를
 * 신뢰하므로 code 재교환이 없다. state 는 완료 흐름과 동일하게 요청자 본인 것인지 재검증한다.
 */
@Component
@ConditionalOnGithubEnabled
class GetGithubConnectionCandidatesUseCase(
    private val stateCodec: GithubInstallStateCodec,
    private val selectionCodec: GithubConnectionSelectionCodec,
    private val githubApi: GithubAppApiPort,
    private val connections: GithubConnectionRepositoryPort,
    private val userAuthorizations: GithubUserAuthorizationPort,
    private val workspaceQuery: WorkspaceQueryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Command(val userId: Long, val code: String, val state: String)

    /** 후보 레포 한 건 — 설치 grant ∩ 사용자 접근 가능. [eligible] = 이 공간에 실제로 선택 가능한지(서버 판정). */
    data class CandidateRepository(
        val repoId: Long,
        val fullName: String,
        val isPrivate: Boolean,
        /** 이 공간(개인=read / 워크스페이스=admin)에서 선택 가능. false 면 FE 가 비활성·사유 표시. */
        val eligible: Boolean,
        /** 사용자 권한: ADMIN | WRITE | READ. */
        val permission: String,
    )

    data class Candidate(
        val installationId: Long,
        val accountLogin: String,
        val accountType: String,
        /** 설치 grant 중 이 사용자가 볼 수 있는 레포(접근 불가 레포는 이름 노출 방지로 제외). */
        val repositories: List<CandidateRepository>,
        /** 이 공간에 이미 ACTIVE 로 연결돼 있나(FE 에서 "이미 연결됨" 표시용). */
        val alreadyConnected: Boolean,
        /** 이 설치를 이 공간으로 연결할 때 [CreateGithubConnectionUseCase] 에 넘길 서명 토큰. */
        val candidateToken: String,
    )

    data class Result(val workspaceId: Long?, val candidates: List<Candidate>)

    fun invoke(command: Command): Result {
        val state = stateCodec.verify(command.state)
        if (state.userId != command.userId) {
            throw GithubInstallStateInvalidException("다른 사용자가 시작한 연결입니다 — 연결을 다시 시작하세요")
        }
        state.workspaceId?.let { requireWorkspaceAdmin(workspaceQuery, command.userId, it) }
        val space: GithubSpace = state.workspaceId?.let(GithubSpace::Workspace) ?: GithubSpace.Personal(command.userId)

        val userToken = githubApi.exchangeUserCode(command.code)
        // 재연결 시 GitHub 재이동 없이 쓰려고 사용자 토큰을 암호화 저장(어댑터가 암호화). GitHub 숫자 id 로 revoked 웹훅과 잇는다.
        val ghUser = githubApi.getAuthenticatedUser(userToken)
        val now = Instant.now(clock)
        userAuthorizations.save(
            GithubUserAuthorization(
                eaUserId = command.userId,
                githubUserId = ghUser.id,
                githubLogin = ghUser.login,
                token = userToken,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val installationIds = githubApi.listUserInstallationIds(userToken)

        val candidates = installationIds.map { installationId ->
            val info = githubApi.getInstallation(installationId)
            val grant = githubApi.listInstallationRepositories(installationId)
            // 사용자 토큰으로 본 접근 권한과 교집합 — grant 에만 있고 사용자가 못 보는 레포는 후보에서 제외(이름 노출 방지).
            val userPerm = githubApi.listUserInstallationRepositories(userToken, installationId).associateBy { it.id }
            val repositories = grant.mapNotNull { r ->
                val perm = userPerm[r.id]?.takeIf { it.canPull } ?: return@mapNotNull null
                CandidateRepository(
                    repoId = r.id,
                    fullName = r.fullName,
                    isPrivate = r.isPrivate,
                    eligible = eligibleForSpace(space, perm),
                    permission = permissionLabel(perm),
                )
            }
            val eligibleRepoIds = repositories.filter { it.eligible }.map { it.repoId }
            Candidate(
                installationId = installationId,
                accountLogin = info.accountLogin,
                accountType = info.accountType,
                repositories = repositories,
                alreadyConnected = connections.findActive(installationId, space) != null,
                candidateToken = selectionCodec.issue(command.userId, state.workspaceId, installationId, eligibleRepoIds),
            )
        }
        return Result(state.workspaceId, candidates)
    }

    /** 개인 공간은 read(pull) 이상이면 선택 가능, 워크스페이스에 공유하려면 그 레포의 admin 권한이 필요(보수적 기본 정책, §2-B). */
    private fun eligibleForSpace(space: GithubSpace, perm: GithubAppApiPort.UserRepositoryInfo): Boolean =
        when (space) {
            is GithubSpace.Personal -> perm.canPull
            is GithubSpace.Workspace -> perm.canAdmin
        }

    private fun permissionLabel(perm: GithubAppApiPort.UserRepositoryInfo): String =
        when {
            perm.canAdmin -> "ADMIN"
            perm.canPush -> "WRITE"
            else -> "READ"
        }
}
