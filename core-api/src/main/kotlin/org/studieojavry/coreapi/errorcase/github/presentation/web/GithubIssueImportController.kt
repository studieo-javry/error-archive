package org.studieojavry.coreapi.errorcase.github.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubImportInvalidRequestException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubImportVisibilityException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubItemNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubRepositoryNotConnectedException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubScopeMismatchException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubUrlInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.ImportGithubIssueUseCase
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.CreateCaseFromGithubIssueRequest
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.CreateCaseFromGithubIssueResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubIssuePreviewResponse

@Tag(
    name = "error-case-github",
    description = "Case 의 GitHub 근거. Issue·PR 주소를 붙이면 PR 상태와 CI 결과가 근거로 쌓이고 타임라인으로 보인다(P1).",
)
@RestController
@RequestMapping("/api/v1/error-cases")
@ConditionalOnGithubEnabled
class GithubIssueImportController(
    private val importIssue: ImportGithubIssueUseCase,
) {

    @Operation(
        summary = "GitHub Issue 로 Case 만들기 — 미리보기",
        description = """
            Issue 를 읽어 Case 초안을 만들어 돌려준다(저장하지 않음). FE 는 이 값을 폼에 채워 사용자가 확인·수정하게 한다.

            - 제목 = Issue 제목
            - Error Trace(`paste`) = **에러 로그로 보이는 코드 블록만**. 재현 명령·설정·예시 코드는 본문에 남김. 전체 블록은 `codeBlocks`(errorLog 표시)
            - 본문 = Issue 본문(옮긴 블록 자리는 안내 한 줄, 체크리스트·`_No response_` 섹션 제외) + 출처 한 줄
            - **자동으로 넣지 않고 후보로만**: 태그(`tagSuggestions` — 상태·종류·우선순위 라벨 제외), 심각도(`severitySuggestion`),
              우회 방법 Step(`workaroundStep`), 보고 시각(`reportedAt` — 발생 시각으로 쓰지 않음)
            - **Case 범위는 저장소 연결 범위로 정해진다**: 워크스페이스에 연결된 저장소 → 그 워크스페이스 Case(WRITE+ 필요),
              개인으로 연결된 저장소 → 연결한 본인의 개인 Case
            - 비공개 저장소 Issue 는 `visibilityOptions` 에 PUBLIC 이 없다
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "미리보기"),
        ApiResponse(responseCode = "400", description = "지원하지 않는 주소 / PR 주소", content = [Content()]),
        ApiResponse(responseCode = "403", description = "저장소가 GitHub 연결에 없음 / 범위 불일치 / 워크스페이스 WRITE 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "GitHub 에 Issue 없음", content = [Content()]),
        ApiResponse(responseCode = "503", description = "GitHub API 사용 불가", content = [Content()]),
    )
    @GetMapping("/github-issue-preview")
    fun preview(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "GitHub Issue 주소", example = "https://github.com/acme/payment-api/issues/142") @RequestParam url: String,
        @Parameter(description = "확인용 워크스페이스 ID — 저장소 연결 범위와 다르면 403") @RequestParam(required = false) workspaceId: Long?,
    ): GithubIssuePreviewResponse = translate { GithubIssuePreviewResponse.of(importIssue.preview(userId, url, workspaceId)) }

    @Operation(
        summary = "GitHub Issue 로 Case 만들기",
        description = """
            미리보기에서 확인한 값으로 Case(OPEN)를 만들고 Issue 를 연결(출처 IMPORTED)한다.
            생략한 태그는 빈 목록, 심각도·발생 시각은 비움. `includeWorkaroundStep=true` 면 우회 방법으로 첫 Step(IN_PROGRESS)을 만들고 Case 는 IN_PROGRESS.
            이어서 **그 Issue 를 닫는 PR 을 함께 붙인다(소급)** — 개수는 `backfilledPullRequests`.
            비공개 저장소 Issue 로 PUBLIC Case 는 만들 수 없다(400).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성됨"),
        ApiResponse(responseCode = "400", description = "지원하지 않는 주소 / PR 주소 / 고를 수 없는 공개 범위 / 우회 방법 섹션 없음 / 심각도 범위 밖", content = [Content()]),
        ApiResponse(responseCode = "403", description = "저장소가 GitHub 연결에 없음 / 범위 불일치 / 워크스페이스 권한 부족", content = [Content()]),
        ApiResponse(responseCode = "404", description = "GitHub 에 Issue 없음", content = [Content()]),
        ApiResponse(responseCode = "503", description = "GitHub API 사용 불가", content = [Content()]),
    )
    @PostMapping("/from-github-issue")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CreateCaseFromGithubIssueRequest,
    ): CreateCaseFromGithubIssueResponse = translate {
        CreateCaseFromGithubIssueResponse.of(
            importIssue.create(
                ImportGithubIssueUseCase.Command(
                    userId, request.url, request.workspaceId, request.visibility,
                    request.title, request.description, request.paste, request.tags,
                    request.severity, request.occurredAt, request.includeWorkaroundStep,
                ),
            ),
        )
    }

    /** GitHub 연동 고유 예외 → HTTP. 워크스페이스 권한 부족 등은 ErrorCaseExceptionHandler 가 처리. */
    private fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: GithubUrlInvalidException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
    } catch (e: GithubImportVisibilityException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
    } catch (e: GithubImportInvalidRequestException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
    } catch (e: GithubRepositoryNotConnectedException) {
        throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
    } catch (e: GithubScopeMismatchException) {
        throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
    } catch (e: GithubItemNotFoundException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    } catch (e: GithubApiException) {
        throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
    }
}
