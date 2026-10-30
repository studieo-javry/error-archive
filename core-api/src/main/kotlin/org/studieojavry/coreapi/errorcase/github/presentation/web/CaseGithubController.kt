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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.usecase.CaseGithubLinkNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.usecase.DetachGithubLinkUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubActivityReader
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubEventCursorInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GetCaseGithubEvidenceUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubItemNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubRepositoryNotConnectedException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubScopeMismatchException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubStepLinkInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubUrlInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.LinkGithubToCaseUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.ListCaseGithubEventsUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.SetStepGithubLinksUseCase
import org.studieojavry.coreapi.errorcase.step.application.usecase.StepNotFoundException
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.CaseGithubEvidenceResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.CaseGithubLinkResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.GithubActivityResponse
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.LinkGithubRequest
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.SetStepGithubLinksRequest
import org.studieojavry.coreapi.errorcase.github.presentation.web.dto.StepGithubLinksResponse

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
    private val setStepLinks: SetStepGithubLinksUseCase,
    private val listEvents: ListCaseGithubEventsUseCase,
) {

    @Operation(
        summary = "GitHub Issue/PR 연결",
        description = """
            Case 에 GitHub Issue 또는 PR 주소를 붙인다. 케이스 쓰기 권한 필요(소유자 또는 워크스페이스 WRITE+). 워크스페이스 Case 는 소유자여도 **현재 워크스페이스 멤버**여야 한다.

            **연결 조건**
            - 레포가 GitHub 연결(App 설치)에 포함되어 있어야 한다.
            - **레포 연결 범위 = Case 범위**: 워크스페이스에 연결된 레포는 그 워크스페이스 Case 에만, 개인 연결 레포는 연결한 본인의 개인 Case 에만.

            **자동 동작**
            - GitHub 에서 최신 정보를 가져와 저장. PR 이면 최신 커밋의 CI 결과도 함께 가져온다.
            - 타임라인에 연결·PR 열림/머지/닫힘·CI 결과를 기록.
            - 이미 연결돼 있으면 200(created=false), "관련 없음"으로 뗐던 것이면 다시 붙이고 201.
            - **Issue 를 붙이면 그 Issue 를 닫는 PR 도 함께 붙는다(소급)** — 이미 열려 있던 PR, GitHub 사이드바에서 연결한 PR 포함.
              단순 언급만 한 PR 은 제외, "관련 없음"으로 뗐던 PR 은 다시 붙이지 않음. 결과는 `backfill.attached`(붙은 PR 목록 `backfill.pullRequests`, 뗐던 탓에 건너뛴 PR `backfill.skipped`),
              GitHub 오류로 확인 못 한 후보는 `backfill.failed`. 소급 부착은 응답 전에 끝난다 — 응답 뒤 근거 조회 한 번이면 Issue 아래 PR 과 활동이 함께 보인다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "새로 연결됨 (또는 뗐던 연결 복구)"),
        ApiResponse(responseCode = "200", description = "이미 연결돼 있음"),
        ApiResponse(responseCode = "400", description = "지원하지 않는 주소", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 쓰기 권한 없음 / 워크스페이스를 떠난 소유자 / 레포가 GitHub 연결에 없음 / 레포 범위와 Case 범위가 다름", content = [Content()]),
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
            .body(CaseGithubLinkResponse.of(result.link, result.created, result.backfill))
    }

    @Operation(
        summary = "GitHub 연결 떼기 (관련 없음)",
        description = "연결을 뗀다. 기록은 남겨서 같은 PR 이 나중에 자동으로 다시 붙지 않는다. 같은 주소를 다시 연결하면 복구된다. 그 근거의 Step 꼬리표는 지워지고 복구되지 않는다. 케이스 쓰기 권한 필요(워크스페이스 Case 는 현재 멤버).",
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
            Case 상세의 GitHub 영역을 **한 번에**: 관계형 근거(Issue ▸ PR, +CI 요약) + GitHub 활동 첫 쪽(`activity`, 최신 20건 + 커서).
            케이스 읽기 권한 필요. 연결·떼기 뒤에도 이것 하나만 다시 부르면 된다. 이전 활동은 `GET .../github/events?cursor=`.

            **같은 시점**: 근거와 활동을 한 DB 스냅샷에서 읽어, 사이에 웹훅이 처리돼도 한 응답 안에서 어긋나지 않는다.
            GitHub API 는 부르지 않는다(DB 만) — GitHub 장애와 무관.

            **관계형 묶음(위치)**: PR 은 ① 경유한 Issue → ② 그 PR 이 닫는 다른 Issue(먼저 연결된 순) 중 이 Case 에 붙어 있는 것 아래에 한 번만.
            없으면 `ungroupedPullRequests`. 위치와 출처는 별개 — 직접 연결한 PR 도 닫는 Issue 가 있으면 그 아래, 출처는 `linkSource`.
            PR·Issue 중 하나라도 요청자에게 가려지면 묶지 않는다(관계 자체가 비공개 정보). `closesIssues` 로 함께 닫는 다른 Issue 를 알 수 있다.

            **비공개 가림**: 비공개 레포 근거의 상세(제목·주소·레포·번호·CI 실패 이름)는
            워크스페이스 Case 면 그 워크스페이스의 **현재** 멤버(역할 무관 — 주인이어도 탈퇴했으면 가림), 개인 Case 면 주인에게만 보인다.
            그 외에게는 `masked=true` + 종류·상태(+`stepIds`)만 내려가고, `maskedCount` 로 개수를 알려준다.

            **Step 꼬리표**: 각 항목의 `stepIds` 는 그 근거가 붙은 Step. 비어 있으면 Case 전체 근거.
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

    @Operation(
        summary = "Case 의 이전 GitHub 활동 조회 (커서)",
        description = """
            "이전 GitHub 이력 더 보기". 근거 조회의 `activity.nextCursor`(또는 이 API 의 `nextCursor`)를 넘기면 그보다 오래된 쪽을 준다.
            케이스 읽기 권한 필요. 활동은 모두 보존되고, 화면이 실제로 보는 구간만 가져온다. 근거 조회의 `activity` 와 같은 로직·같은 모양.

            - 정렬: GitHub 쪽 발생 시각 내림차순, 같은 시각은 id 내림차순. `nextCursor=null` 이면 끝. `cursor` 없이 부르면 첫 쪽.
            - 데이터가 바뀌지 않는 동안에는 겹침·빠짐이 없다. 넘기는 중에 새 최신 활동이 생겨도 이어 받는 쪽에는 섞이지 않는다(첫 쪽 재조회에서 보임).
            - **완전성은 보장하지 않는다**: 이미 지나간 구간에 늦게 기록된 과거 활동(소급 연결)은 이어 받기로는 안 나오고,
              CI 결과가 바뀌며 시각이 옮겨지면 같은 id 가 다시 나오거나(id 로 합칠 것) 아직 안 받은 구간에서 빠질 수 있다.
              끝까지 받은 고유 id 수가 `totalCount` 보다 적으면 처음부터 다시 받는다. `hasNext` 가 끝 판단 기준, `totalCount` 는 안내용.
            - `totalCount` 는 지금 기준 전체 건수(가려진 줄 포함). 쪽마다 바뀔 수 있다.
            - 비공개 가림은 근거 조회와 같다: 가려진 줄은 `masked=true` + 종류·시각(CI 는 결과만).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 커서", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @GetMapping("/events")
    fun events(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "이전 응답의 nextCursor. 첫 쪽이면 생략") @RequestParam(required = false) cursor: String?,
        @Parameter(description = "한 쪽 크기(1~100, 범위 밖이면 가까운 값으로). 기본 20")
        @RequestParam(defaultValue = GithubActivityReader.DEFAULT_SIZE.toString()) size: Int,
    ): GithubActivityResponse = GithubActivityResponse.of(translate { listEvents.invoke(caseId, userId, cursor, size) })

    @Operation(
        summary = "Step 의 GitHub 근거 꼬리표 설정",
        description = """
            Step 에 "이 시도의 근거" 꼬리표를 단다. 요청 목록으로 **통째로 맞춘다**(빈 목록 = 모두 해제). 케이스 쓰기 권한 필요(워크스페이스 Case 는 현재 멤버).

            - 고를 수 있는 건 **이미 이 Case 에 붙어 있는(떼지 않은) 근거**의 연결 id 뿐. 아니면 400.
            - 근거 하나를 여러 Step 에 달 수 있다. 내용은 복사하지 않는다(비공개 가림은 근거 조회 규칙 그대로).
            - 요청자에게 가려진(비공개) 근거의 꼬리표는 요청과 무관하게 그대로 둔다. 응답 linkIds 에도 보이는 것만.
            - Case 에서 근거를 떼면 꼬리표도 사라지고, 다시 연결해도 복구되지 않는다. Step 을 지우면 그 Step 의 꼬리표도 사라진다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "설정됨"),
        ApiResponse(responseCode = "400", description = "이 Case 의 활성 근거가 아닌 연결 id 포함 / 50개 초과", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 쓰기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음 / 이 Case 의 Step 이 아님", content = [Content()]),
    )
    @PutMapping("/steps/{stepId}/links")
    fun setStepLinks(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "Step ID") @PathVariable stepId: Long,
        @Valid @RequestBody request: SetStepGithubLinksRequest,
    ): StepGithubLinksResponse =
        StepGithubLinksResponse(stepId, translate { setStepLinks.invoke(caseId, stepId, userId, request.linkIds) })

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
    } catch (e: GithubStepLinkInvalidException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
    } catch (e: GithubEventCursorInvalidException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
    } catch (e: StepNotFoundException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    } catch (e: CaseGithubLinkNotFoundException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    } catch (e: GithubApiException) {
        throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
    }
}
