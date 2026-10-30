package org.studieojavry.coreapi.errorcase.github.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.usecase.CompleteGithubInstallationUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubInstallStateInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubInstallationAlreadyBoundException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubInstallationNotOwnedException
import org.studieojavry.coreapi.errorcase.github.application.usecase.IssueGithubInstallUrlUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.ListMyGithubInstallationsUseCase
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.CompleteGithubInstallationRequest
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubInstallUrlResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubInstallationResponse

@Tag(
    name = "github-integration",
    description = "GitHub App 연결. 연결된 레포의 Issue·PR·CI 가 Case 근거로 쌓이는 기능의 기반(P0).",
)
@RestController
@RequestMapping("/api/v1/integrations/github")
@ConditionalOnGithubEnabled
class GithubInstallationController(
    private val issueInstallUrl: IssueGithubInstallUrlUseCase,
    private val completeInstallation: CompleteGithubInstallationUseCase,
    private val listMyInstallations: ListMyGithubInstallationsUseCase,
) {

    @Operation(
        summary = "GitHub 연결 시작 — 설치 페이지 URL 발급",
        description = "서명된 state(10분)를 포함한 GitHub App 설치 URL. workspaceId 를 주면 그 워크스페이스 범위로 연결(ADMIN 필요).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(responseCode = "401", description = "미인증"),
        ApiResponse(responseCode = "403", description = "워크스페이스 ADMIN 아님 / 게스트"),
    )
    @GetMapping("/install-url")
    fun installUrl(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "연결할 워크스페이스 ID (생략 시 개인 연결)") @RequestParam(required = false) workspaceId: Long?,
    ): GithubInstallUrlResponse = GithubInstallUrlResponse(issueInstallUrl.invoke(userId, workspaceId))

    @Operation(
        summary = "GitHub 연결 완료",
        description = "GitHub 설치 후 FE(Setup URL)로 돌아온 installation_id·code·state 를 전달. " +
            "state 가 요청자 본인 것인지, 그 GitHub 사용자가 설치에 실제 접근 가능한지 확인 후 설치·레포를 저장한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "연결됨 (재연결 포함)"),
        ApiResponse(responseCode = "400", description = "state 위조·만료·다른 사용자 것 / 잘못된 code"),
        ApiResponse(responseCode = "403", description = "이 GitHub 계정이 접근할 수 없는 설치 / 워크스페이스 ADMIN 아님"),
        ApiResponse(responseCode = "409", description = "이미 다른 사용자·워크스페이스에 연결된 설치"),
        ApiResponse(responseCode = "503", description = "GitHub API 사용 불가"),
    )
    @PostMapping("/installations")
    fun complete(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CompleteGithubInstallationRequest,
    ): GithubInstallationResponse {
        val result = try {
            completeInstallation.invoke(
                CompleteGithubInstallationUseCase.Command(userId, request.installationId, request.code, request.state),
            )
        } catch (e: GithubInstallStateInvalidException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: GithubInstallationNotOwnedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: GithubInstallationAlreadyBoundException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
        } catch (e: GithubApiException) {
            val status = if (e.clientError) HttpStatus.BAD_REQUEST else HttpStatus.SERVICE_UNAVAILABLE
            throw ResponseStatusException(status, e.message, e)
        }
        return GithubInstallationResponse.of(result.installation, result.repositories)
    }

    @Operation(
        summary = "내 GitHub 연결 목록",
        description = "내가 연결했거나 내가 속한 워크스페이스에 연결된 설치와 활성 레포.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "401", description = "미인증"),
    )
    @GetMapping("/installations")
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
    ): List<GithubInstallationResponse> = listMyInstallations.invoke(userId).map(GithubInstallationResponse::of)
}
