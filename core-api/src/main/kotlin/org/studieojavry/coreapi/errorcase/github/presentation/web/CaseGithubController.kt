package org.studieojavry.coreapi.errorcase.github.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.usecase.CaseGithubLinkNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.usecase.DetachGithubLinkUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.GetCaseGithubEvidenceUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubItemNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubRepositoryNotConnectedException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubScopeMismatchException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubUrlInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.LinkGithubToCaseUseCase
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.CaseGithubEvidenceResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.CaseGithubLinkResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.LinkGithubRequest

@Tag(
    name = "error-case-github",
    description = "Case 의 GitHub 근거. Issue·PR 주소를 붙이면 PR 상태와 CI 결과가 근거로 쌓이고 타임라인으로 보인다(P1).",
)
@RestController
@RequestMapping("/api/v1/error-cases/{caseId}/github")
@ConditionalOnGithubEnabled
class CaseGithubController(
    private val linkGithub: LinkGithubToCaseUseCase,
    private val detachGithub: DetachGithubLinkUseCase,
    private val getEvidence: GetCaseGithubEvidenceUseCase,
) {

    @Operation(
        summary = "GitHub Issue/PR 연결",
        description = """
            Case 에 GitHub Issue 또는 PR 주소를 붙인다. 케이스 쓰기 권한 필요(소유자 또는 워크스페이스 WRITE+).

            **연결 조건**
            - 레포가 GitHub 연결(App 설치)에 포함되어 있어야 한다.
            - **레포 연결 범위 = Case 범위**: 워크스페이스에 연결된 레포는 그 워크스페이스 Case 에만, 개인 연결 레포는 연결한 본인의 개인 Case 에만.

            **자동 동작**
            - GitHub 에서 최신 정보를 가져와 저장. PR 이면 최신 커밋의 CI 결과도 함께 가져온다.
            - 타임라인에 연결·PR 열림/머지/닫힘·CI 결과를 기록.
            - 이미 연결돼 있으면 200(created=false), "관련 없음"으로 뗐던 것이면 다시 붙이고 201.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "새로 연결됨 (또는 뗐던 연결 복구)"),
        ApiResponse(responseCode = "200", description = "이미 연결돼 있음"),
        ApiResponse(responseCode = "400", description = "지원하지 않는 주소", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 쓰기 권한 없음 / 레포가 GitHub 연결에 없음 / 레포 범위와 Case 범위가 다름", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음 / GitHub 에 Issue·PR 없음", content = [Content()]),
        ApiResponse(responseCode = "503", description = "GitHub API 사용 불가", content = [Content()]),
    )
    @PostMapping("/links")
    fun link(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Valid @RequestBody request: LinkGithubRequest,
    ): ResponseEntity<CaseGithubLinkResponse> {
        val result = translate { linkGithub.invoke(caseId, userId, request.url) }
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(CaseGithubLinkResponse.of(result.link, result.created))
    }

    @Operation(
        summary = "GitHub 연결 떼기 (관련 없음)",
        description = "연결을 뗀다. 기록은 남겨서 같은 PR 이 나중에 자동으로 다시 붙지 않는다. 같은 주소를 다시 연결하면 복구된다. 케이스 쓰기 권한 필요.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "뗐음 (이미 뗀 연결이어도 204)"),
        ApiResponse(responseCode = "403", description = "케이스 쓰기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 또는 연결 없음", content = [Content()]),
    )
    @DeleteMapping("/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun detach(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "연결 ID") @PathVariable linkId: Long,
    ) {
        translate { detachGithub.invoke(caseId, linkId, userId) }
    }

    @Operation(
        summary = "Case 의 GitHub 근거 조회",
        description = """
            연결된 Issue·PR(+CI 요약)과 근거 타임라인. 케이스 읽기 권한 필요.

            **비공개 가림**: 비공개 레포 근거의 상세(제목·주소·레포·번호·CI 실패 이름)는 Case 주인과 Case 워크스페이스 멤버에게만 보인다.
            그 외에게는 `masked=true` + 종류·상태만 내려가고, `maskedCount` 로 개수를 알려준다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "케이스 읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @GetMapping
    fun evidence(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
    ): CaseGithubEvidenceResponse = CaseGithubEvidenceResponse.of(getEvidence.invoke(caseId, userId))

    /** GitHub 연동 고유 예외 → HTTP. Case 없음/권한 없음은 ErrorCaseExceptionHandler 가 처리. */
    private fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: GithubUrlInvalidException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
    } catch (e: GithubRepositoryNotConnectedException) {
        throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
    } catch (e: GithubScopeMismatchException) {
        throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
    } catch (e: GithubItemNotFoundException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    } catch (e: CaseGithubLinkNotFoundException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    } catch (e: GithubApiException) {
        throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
    }
}
