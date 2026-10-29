package org.studieojavry.coreapi.errorcase.github.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubReferenceScope
import org.studieojavry.coreapi.errorcase.github.application.usecase.ResolveGithubReferencesUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.SearchGithubReferencesUseCase
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubReferenceRepositoriesResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubReferenceSearchResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubReferencesResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.ResolveGithubReferencesRequest

@Tag(
    name = "github-reference",
    description = "본문 GitHub 참조. Case 본문·Step 에 `#` 로 Issue·PR 을 골라 `owner/repo#N` 으로 적고, 읽기 화면에서 제목·상태로 보여준다. 참조는 인용일 뿐 Case 근거 연결과 별개.",
)
@RestController
@ConditionalOnGithubEnabled
class GithubReferenceController(
    private val scope: GithubReferenceScope,
    private val search: SearchGithubReferencesUseCase,
    private val resolve: ResolveGithubReferencesUseCase,
) {

    @Operation(
        summary = "# 자동완성 대상 레포",
        description = """
            Case 가 속한(생성 화면이면 속할) 공간에 연결된 활성 레포. 레포 공개 여부와 무관.
            - `workspaceId` 있음: 그 워크스페이스에 연결된 레포. 요청자가 **현재 멤버**여야 한다.
            - 없음(개인 Case): 요청자가 개인으로 연결한 레포.

            비어 있으면 FE 는 자동완성을 띄우지 않는다 — `#95` 는 일반 텍스트로 저장된다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "워크스페이스 멤버 아님", content = [Content()]),
    )
    @GetMapping("/api/v1/integrations/github/reference-repositories")
    fun repositories(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "Case 공간 워크스페이스 ID. 개인 Case 면 생략") @RequestParam(required = false) workspaceId: Long?,
    ): GithubReferenceRepositoriesResponse = GithubReferenceRepositoriesResponse.of(scope.repositories(userId, workspaceId))

    @Operation(
        summary = "# 자동완성 Issue·PR 검색",
        description = """
            공간에 연결된 레포에서 Issue·PR 을 찾는다(GitHub 스타일). `q` 는 `#` 뒤에 입력한 글자.
            - 비어 있음: 최근 갱신된 Issue·PR
            - 숫자(`95`): 각 레포의 95번 그 자체 + 제목에 95 가 들어간 것
            - 글자(`결제`): 제목 검색

            결과는 레포 이름 → 번호 순 최대 8개. 고른 결과는 본문에 `{repo}#{number}` 로 들어간다(**고르지 않은 `#95` 는 일반 텍스트**).
            GitHub 오류가 난 레포는 결과에서 빠진다. 같은 질의는 30초 동안 재사용.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "검색 성공 (결과 없음 포함)"),
        ApiResponse(responseCode = "403", description = "워크스페이스 멤버 아님", content = [Content()]),
    )
    @GetMapping("/api/v1/integrations/github/reference-search")
    fun search(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "Case 공간 워크스페이스 ID. 개인 Case 면 생략") @RequestParam(required = false) workspaceId: Long?,
        @Parameter(description = "`#` 뒤 입력(번호 또는 제목, 최대 40자)") @RequestParam(required = false) q: String?,
    ): GithubReferenceSearchResponse = GithubReferenceSearchResponse.of(search.invoke(userId, workspaceId, q))

    @Operation(
        summary = "본문 GitHub 참조 조회",
        description = """
            Case 본문·Step 에 적힌 `owner/repo#N` → 읽기 화면용 제목·종류·상태. 케이스 읽기 권한 필요. 저장·타임라인 기록 없음.
            - 레포가 그 Case 공간에 연결돼 있지 않거나 GitHub 에 없으면 `resolved=false`
            - 비공개 레포인데 요청자가 비공개 근거를 볼 수 없으면(근거 조회의 가림과 같은 기준) `masked=true` — 제목·상태 없음
            - 짧은 `#N` 은 참조가 아니다(요청에 넣어도 결과에서 빠진다). 최대 50개, 결과는 60초 재사용.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "케이스 읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @PostMapping("/api/v1/error-cases/{caseId}/github/references")
    fun resolve(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Valid @RequestBody request: ResolveGithubReferencesRequest,
    ): GithubReferencesResponse = GithubReferencesResponse.of(resolve.invoke(caseId, userId, request.refs))
}
