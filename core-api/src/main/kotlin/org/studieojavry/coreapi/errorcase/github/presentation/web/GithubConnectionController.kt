package org.studieojavry.coreapi.errorcase.github.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.errorcase.github.application.usecase.CreateGithubConnectionUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.DisconnectGithubConnectionUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.GetGithubConnectionCandidatesUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.GetGithubConnectionOptionsUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.IssueGithubAuthorizeUrlUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.ListGithubConnectionsUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.RefreshInstallationRepositoriesUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.UpdateConnectionRepositoriesUseCase
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.CreateGithubConnectionRequest
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubAuthorizeUrlResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubConnectionCandidatesRequest
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubConnectionCandidatesResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubConnectionOptionsResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubConnectionResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.RefreshRepositoriesResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.UpdateConnectionRepositoriesRequest

/**
 * GitHub "연결(공간 바인딩)" 흐름 — 이미 설치된 App 을 OAuth 로 조회해 공간에 붙이고, 공간별로 해제·레포 선택을 관리한다.
 * (installations/new 설치 흐름은 [GithubInstallationController].) 모두 인증+게스트 금지(integrations 하위 보안 규칙 상속).
 */
@Tag(
    name = "github-integration",
    description = "GitHub 연결(공간별). 설치 1건을 개인·여러 워크스페이스에 동시 연결하고 연결별로 사용할 레포를 고른다.",
)
@RestController
@RequestMapping("/api/v1/integrations/github")
@ConditionalOnGithubEnabled
class GithubConnectionController(
    private val issueAuthorizeUrl: IssueGithubAuthorizeUrlUseCase,
    private val getOptions: GetGithubConnectionOptionsUseCase,
    private val getCandidates: GetGithubConnectionCandidatesUseCase,
    private val createConnection: CreateGithubConnectionUseCase,
    private val listConnections: ListGithubConnectionsUseCase,
    private val updateRepositories: UpdateConnectionRepositoriesUseCase,
    private val disconnectConnection: DisconnectGithubConnectionUseCase,
    private val refreshRepositories: RefreshInstallationRepositoriesUseCase,
) {

    @Operation(
        summary = "연결 옵션 조회 (시나리오 ②)",
        description = "저장된 사용자 토큰으로 상황을 구분한다. VERIFIED_AVAILABLE 면 GitHub 재이동 없이 candidates 로 바로 " +
            "레포를 고를 수 있다. NOT_AUTHORIZED 면 authorizeUrl, VERIFIED_EMPTY(설치 없음)면 installUrl 로 이동. " +
            "workspaceId 를 주면 그 워크스페이스 기준(ADMIN 필요).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "상태 + (후보 또는 이동 URL)"),
        ApiResponse(responseCode = "401", description = "미인증"),
        ApiResponse(responseCode = "403", description = "워크스페이스 ADMIN 아님 / 게스트"),
    )
    @GetMapping("/connection-options")
    fun connectionOptions(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "연결할 워크스페이스 ID (생략 시 개인 연결)") @RequestParam(required = false) workspaceId: Long?,
    ): GithubConnectionOptionsResponse = GithubConnectionOptionsResponse.of(getOptions.invoke(userId, workspaceId))

    @Operation(
        summary = "연결 시작 — OAuth 인가 URL 발급",
        description = "이미 설치된 GitHub App 을 (이) 공간에 연결하기 위한 인가 URL. installations/new 와 달리 이미 설치된 " +
            "계정에서도 막다른 길(Save 비활성)에 빠지지 않는다. workspaceId 를 주면 그 워크스페이스로 연결(ADMIN 필요).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(responseCode = "401", description = "미인증"),
        ApiResponse(responseCode = "403", description = "워크스페이스 ADMIN 아님 / 게스트"),
    )
    @GetMapping("/authorize-url")
    fun authorizeUrl(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "연결할 워크스페이스 ID (생략 시 개인 연결)") @RequestParam(required = false) workspaceId: Long?,
    ): GithubAuthorizeUrlResponse = GithubAuthorizeUrlResponse(issueAuthorizeUrl.invoke(userId, workspaceId))

    @Operation(
        summary = "연결 후보 조회",
        description = "OAuth 콜백의 code·state 로, 이 사용자가 접근 가능한 설치들을 후보로 나열(설치 화면 없이). " +
            "설치마다 grant 레포와 candidateToken 을 돌려준다. code 는 여기서 한 번만 사용.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "후보 목록"),
        ApiResponse(responseCode = "400", description = "state 위조·만료·다른 사용자 것 / 잘못된 code"),
        ApiResponse(responseCode = "403", description = "워크스페이스 ADMIN 아님 / 게스트"),
        ApiResponse(responseCode = "429", description = "GitHub 호출 한도 초과 — Retry-After(초) 뒤 다시 시도"),
        ApiResponse(responseCode = "503", description = "GitHub API 사용 불가"),
    )
    @PostMapping("/connection-candidates")
    fun connectionCandidates(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: GithubConnectionCandidatesRequest,
    ): GithubConnectionCandidatesResponse = GithubConnectionCandidatesResponse.of(
        getCandidates.invoke(GetGithubConnectionCandidatesUseCase.Command(userId, request.code, request.state)),
    )

    @Operation(
        summary = "연결 생성",
        description = "candidateToken + 선택 레포로 설치를 공간에 연결한다(버그의 실제 수정). 고른 레포는 설치 grant 의 " +
            "부분집합이어야 하며, 같은 공간에 이미 연결돼 있으면 409.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "연결됨(재연결 포함)"),
        ApiResponse(responseCode = "400", description = "후보 토큰 위조·만료 / grant 밖 레포 선택"),
        ApiResponse(responseCode = "403", description = "워크스페이스 ADMIN 아님 / 게스트"),
        ApiResponse(responseCode = "409", description = "이미 이 공간에 연결된 설치"),
        ApiResponse(responseCode = "429", description = "GitHub 호출 한도 초과 — Retry-After(초) 뒤 다시 시도"),
        ApiResponse(responseCode = "503", description = "GitHub API 사용 불가"),
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/connections")
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CreateGithubConnectionRequest,
    ): GithubConnectionResponse = GithubConnectionResponse.of(
        createConnection.invoke(
            CreateGithubConnectionUseCase.Command(userId, request.candidateToken, request.selectedRepoIds),
        ),
    )

    @Operation(
        summary = "내 연결 목록(관리)",
        description = "내 공간들(개인 + 내가 속한 워크스페이스)의 ACTIVE 연결과 각 연결의 선택 레포 + 선택 가능(grant) 레포.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "401", description = "미인증"),
    )
    @GetMapping("/connections")
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
    ): List<GithubConnectionResponse> = listConnections.invoke(userId).map(GithubConnectionResponse::of)

    @Operation(
        summary = "연결의 레포 선택 변경",
        description = "이 연결에서 사용할 레포를 주어진 집합으로 교체(replace-set). 선택을 빼면 그 공간에선 즉시 반영이 멈춘다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경됨"),
        ApiResponse(responseCode = "400", description = "grant 밖 레포 선택"),
        ApiResponse(responseCode = "403", description = "워크스페이스 ADMIN 아님 / 게스트"),
        ApiResponse(responseCode = "404", description = "없는/비활성 연결 또는 접근 불가"),
        ApiResponse(responseCode = "409", description = "동시 편집 충돌(expectedVersion 불일치)"),
    )
    @PutMapping("/connections/{connectionId}/repositories")
    fun updateRepositories(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "연결 id") @PathVariable connectionId: Long,
        @Valid @RequestBody request: UpdateConnectionRepositoriesRequest,
    ): GithubConnectionResponse = GithubConnectionResponse.of(
        updateRepositories.invoke(
            UpdateConnectionRepositoriesUseCase.Command(
                userId, connectionId, request.selectedRepoIds, request.expectedVersion,
            ),
        ),
    )

    @Operation(
        summary = "설치 저장소 새로고침",
        description = "GitHub Configure 에서 접근 범위를 바꾸고 돌아온 뒤, 설치 grant 를 다시 동기화한다. 새 레포는 " +
            "후보로만 반영하고 어느 공간의 선택에도 자동 추가하지 않는다. 이 설치에 접근 권한 있는 연결(개인=본인/워크스페이스=ADMIN)이 있어야 한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "새로고침됨(추가·제거 레포 id 포함)"),
        ApiResponse(responseCode = "403", description = "게스트"),
        ApiResponse(responseCode = "404", description = "접근 가능한 연결이 없는 설치"),
        ApiResponse(responseCode = "429", description = "GitHub 호출 한도 초과 — Retry-After(초) 뒤 다시 시도"),
        ApiResponse(responseCode = "503", description = "GitHub API 사용 불가"),
    )
    @PostMapping("/installations/{installationId}/refresh-repositories")
    fun refreshRepositories(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "GitHub installation id") @PathVariable installationId: Long,
    ): RefreshRepositoriesResponse = RefreshRepositoriesResponse.of(refreshRepositories.invoke(userId, installationId))

    @Operation(
        summary = "공간별 연결 해제",
        description = "이 공간에서만 연결을 끊는다. 설치·레포·스냅샷은 그대로이며 다른 공간 연결에도 영향이 없다. 멱등(이미 끊겼으면 no-op).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "해제됨(이미 해제된 경우 포함)"),
        ApiResponse(responseCode = "403", description = "워크스페이스 ADMIN 아님 / 게스트"),
        ApiResponse(responseCode = "404", description = "없는 연결 또는 접근 불가"),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/connections/{connectionId}")
    fun disconnect(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "연결 id") @PathVariable connectionId: Long,
    ) = disconnectConnection.invoke(userId, connectionId)
}
