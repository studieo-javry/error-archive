package org.studieojavry.coreapi.errorcase.github.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import org.studieojavry.coreapi.errorcase.github.application.usecase.CreateGithubConnectionUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.GetGithubConnectionCandidatesUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.ListGithubConnectionsUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.RefreshInstallationRepositoriesUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.UpdateConnectionRepositoriesUseCase
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubConnection
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.shared.presentation.toApiOffset
import java.time.OffsetDateTime

@Schema(description = "GitHub OAuth 인가 URL — 이미 설치된 App 을 공간에 연결하기 위해 사용자 토큰을 받는다(설치 화면을 거치지 않음)")
data class GithubAuthorizeUrlResponse(
    @field:Schema(description = "브라우저를 이 URL 로 이동. GitHub 가 인가 후 FE 콜백으로 code·state 를 돌려보낸다.")
    val url: String,
)

@Schema(description = "연결 후보 조회 요청 — OAuth 콜백의 code·state 를 그대로 전달")
data class GithubConnectionCandidatesRequest(
    @field:Schema(description = "OAuth 인가 코드 (`code` 쿼리) — 후보 조회에 한 번만 사용, 저장하지 않음")
    @field:NotBlank
    val code: String,
    @field:Schema(description = "authorize-url 발급 때 받은 서명된 state (`state` 쿼리)")
    @field:NotBlank
    val state: String,
)

@Schema(description = "연결 후보 — 이 사용자가 접근 가능한 설치 1건과 grant 된 레포")
data class GithubConnectionCandidateResponse(
    @field:Schema(description = "GitHub installation id") val installationId: Long,
    @field:Schema(description = "GitHub 계정(org/user) login") val accountLogin: String,
    @field:Schema(description = "Organization | User") val accountType: String,
    @field:Schema(description = "이 공간에 이미 ACTIVE 로 연결돼 있는지") val alreadyConnected: Boolean,
    @field:Schema(description = "레포를 고른 뒤 POST /connections 에 넘길 서명 토큰(짧은 수명)") val candidateToken: String,
    @field:Schema(description = "설치 grant ∩ 사용자 접근 가능 레포(접근 불가 레포는 제외)") val repositories: List<GithubCandidateRepositoryResponse>,
) {
    companion object {
        fun of(c: GetGithubConnectionCandidatesUseCase.Candidate) = GithubConnectionCandidateResponse(
            installationId = c.installationId,
            accountLogin = c.accountLogin,
            accountType = c.accountType,
            alreadyConnected = c.alreadyConnected,
            candidateToken = c.candidateToken,
            repositories = c.repositories.map(GithubCandidateRepositoryResponse::of),
        )
    }
}

@Schema(description = "연결 후보의 레포 — eligible 이 true 여야 이 공간에서 선택 가능")
data class GithubCandidateRepositoryResponse(
    @field:Schema(description = "GitHub repository id") val repoId: Long,
    @field:Schema(description = "owner/name") val fullName: String,
    @field:Schema(description = "비공개 레포 여부") val isPrivate: Boolean,
    @field:Schema(description = "이 공간에서 선택 가능(개인=read / 워크스페이스=admin). false 면 선택 불가") val eligible: Boolean,
    @field:Schema(description = "사용자 권한: ADMIN | WRITE | READ") val permission: String,
) {
    companion object {
        fun of(r: GetGithubConnectionCandidatesUseCase.CandidateRepository) = GithubCandidateRepositoryResponse(
            repoId = r.repoId,
            fullName = r.fullName,
            isPrivate = r.isPrivate,
            eligible = r.eligible,
            permission = r.permission,
        )
    }
}

@Schema(description = "연결 후보 목록")
data class GithubConnectionCandidatesResponse(
    @field:Schema(description = "연결하려는 워크스페이스. null = 개인 연결") val workspaceId: Long?,
    val candidates: List<GithubConnectionCandidateResponse>,
) {
    companion object {
        fun of(result: GetGithubConnectionCandidatesUseCase.Result) = GithubConnectionCandidatesResponse(
            workspaceId = result.workspaceId,
            candidates = result.candidates.map(GithubConnectionCandidateResponse::of),
        )
    }
}

@Schema(description = "연결 생성 요청 — 후보 토큰 + 사용할 레포 선택")
data class CreateGithubConnectionRequest(
    @field:Schema(description = "connection-candidates 가 준 candidateToken")
    @field:NotBlank
    val candidateToken: String,
    @field:Schema(description = "사용 선택할 레포 id (설치 grant 의 부분집합). 비우면 선택 없이 연결만 생성.")
    val selectedRepoIds: List<Long> = emptyList(),
)

@Schema(description = "연결의 레포 선택 변경 요청(replace-set)")
data class UpdateConnectionRepositoriesRequest(
    @field:Schema(description = "이 연결에서 사용할 레포 id 전체(기존 선택을 이 집합으로 교체). 빈 배열이면 전부 해제.")
    val selectedRepoIds: List<Long> = emptyList(),
)

@Schema(description = "설치 저장소 새로고침 결과 — 새 레포는 후보로만(자동 선택되지 않음)")
data class RefreshRepositoriesResponse(
    @field:Schema(description = "GitHub installation id") val installationId: Long,
    @field:Schema(description = "새로고침 후 선택 가능한(설치 grant) 활성 레포 전체") val availableRepositories: List<GithubRepositoryResponse>,
    @field:Schema(description = "이번에 새로 grant 된 레포 id(후보로만)") val addedRepoIds: List<Long>,
    @field:Schema(description = "이번에 grant 에서 빠진 레포 id") val removedRepoIds: List<Long>,
) {
    companion object {
        fun of(r: RefreshInstallationRepositoriesUseCase.Result) = RefreshRepositoriesResponse(
            installationId = r.installation.installationId,
            availableRepositories = r.availableRepositories.map(GithubRepositoryResponse::of),
            addedRepoIds = r.addedRepoIds,
            removedRepoIds = r.removedRepoIds,
        )
    }
}

@Schema(description = "GitHub 공간 연결(관리 화면)")
data class GithubConnectionResponse(
    @field:Schema(description = "연결 id") val connectionId: Long,
    @field:Schema(description = "GitHub installation id") val installationId: Long,
    @field:Schema(description = "GitHub 계정(org/user) login") val accountLogin: String,
    @field:Schema(description = "Organization | User") val accountType: String,
    @field:Schema(description = "연결 범위 워크스페이스. null = 개인 연결") val workspaceId: Long?,
    @field:Schema(description = "연결 상태: ACTIVE | DISCONNECTED") val status: String,
    @field:Schema(description = "이 연결을 만든 사용자(감사용)") val connectedByUserId: Long,
    @field:Schema(description = "사용 선택된 레포") val selectedRepositories: List<GithubRepositoryResponse>,
    @field:Schema(description = "선택 가능한(설치 grant) 레포 전체") val availableRepositories: List<GithubRepositoryResponse>,
    val connectedAt: OffsetDateTime,
) {
    companion object {
        fun of(
            installation: GithubInstallation,
            connection: GithubConnection,
            selectedRepositories: List<GithubRepository>,
            availableRepositories: List<GithubRepository>,
        ) = GithubConnectionResponse(
            connectionId = requireNotNull(connection.connectionId),
            installationId = installation.installationId,
            accountLogin = installation.accountLogin,
            accountType = installation.accountType,
            workspaceId = (connection.space as? GithubSpace.Workspace)?.workspaceId,
            status = connection.status.name,
            connectedByUserId = connection.connectedByUserId,
            selectedRepositories = selectedRepositories.map(GithubRepositoryResponse::of),
            availableRepositories = availableRepositories.map(GithubRepositoryResponse::of),
            connectedAt = connection.createdAt.toApiOffset(),
        )

        fun of(item: ListGithubConnectionsUseCase.Item) =
            of(item.installation, item.connection, item.selectedRepositories, item.availableRepositories)

        fun of(result: CreateGithubConnectionUseCase.Result) =
            of(result.installation, result.connection, result.selectedRepositories, result.availableRepositories)

        fun of(result: UpdateConnectionRepositoriesUseCase.Result) =
            of(result.installation, result.connection, result.selectedRepositories, result.availableRepositories)
    }
}
