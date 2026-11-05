package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

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
    private val workspaceQuery: WorkspaceQueryPort,
) {
    data class Command(val userId: Long, val code: String, val state: String)

    data class Candidate(
        val installationId: Long,
        val accountLogin: String,
        val accountType: String,
        val repositories: List<GithubAppApiPort.RepositoryInfo>,
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
        val installationIds = githubApi.listUserInstallationIds(userToken)

        val candidates = installationIds.map { installationId ->
            val info = githubApi.getInstallation(installationId)
            val repos = githubApi.listInstallationRepositories(installationId)
            Candidate(
                installationId = installationId,
                accountLogin = info.accountLogin,
                accountType = info.accountType,
                repositories = repos,
                alreadyConnected = connections.findActive(installationId, space) != null,
                candidateToken = selectionCodec.issue(command.userId, state.workspaceId, installationId),
            )
        }
        return Result(state.workspaceId, candidates)
    }
}
