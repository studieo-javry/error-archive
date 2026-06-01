package org.studieojavry.coreapi.errorcase.step.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseAccessDeniedException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.step.application.command.CreateStepCommand
import org.studieojavry.coreapi.errorcase.step.application.command.UpdateStepCommand
import org.studieojavry.coreapi.errorcase.step.application.usecase.CreateStepUseCase
import org.studieojavry.coreapi.errorcase.step.application.usecase.DeleteStepUseCase
import org.studieojavry.coreapi.errorcase.step.application.usecase.ListStepsUseCase
import org.studieojavry.coreapi.errorcase.step.application.usecase.StepAccessDeniedException
import org.studieojavry.coreapi.errorcase.step.application.usecase.StepDeleteConflictException
import org.studieojavry.coreapi.errorcase.step.application.usecase.StepNotFoundException
import org.studieojavry.coreapi.errorcase.step.application.usecase.UpdateStepUseCase
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.presentation.web.dto.request.CreateStepRequest
import org.studieojavry.coreapi.errorcase.step.presentation.web.dto.request.UpdateStepRequest
import org.studieojavry.coreapi.errorcase.step.presentation.web.dto.response.CreateStepResponse
import org.studieojavry.coreapi.errorcase.step.presentation.web.dto.response.StepResponse
import org.studieojavry.coreapi.errorcase.step.presentation.web.dto.response.UpdateStepResponse


@Tag(
    name = "error-case-steps",
    description = """
        에러케이스 해결 시도(타임라인 한 칸). 본문은 자유 마크다운, 메타(title/status/insight/attemptType)는 구조화.
        UI 의 '템플릿 헤더 삽입(시도/결과/학습)' 버튼은 본문 마크다운에 헤더 텍스트만 끼워넣음 — 백엔드는 자유 본문 그대로 저장.
    """
)
@RestController
@RequestMapping("/api/v1/error-cases/{caseId}/steps")
class StepController(
    private val createStepUseCase: CreateStepUseCase,
    private val listStepsUseCase: ListStepsUseCase,
    private val updateStepUseCase: UpdateStepUseCase,
    private val deleteStepUseCase: DeleteStepUseCase,
) {

    @Operation(
        summary = "Step 추가",
        description = """
            새 해결 시도를 추가한다. 케이스 쓰기 권한 필요(소유자 또는 워크스페이스 WRITE+).

            **자동 동작**
            - 첫 step + 케이스가 OPEN → **IN_PROGRESS 로 자동 전환**.
            - status=SUCCESS + 케이스가 IN_PROGRESS → 응답 `suggestResolve=true`. SPA 가 사용자에게 RESOLVED 전환 확인 모달 보여주고, 동의 시 `PATCH /error-cases/{id} { status:"RESOLVED" }` 호출.

            **본문(body)**: 자유 마크다운. `@snippet(markerId)`/`@attach(markerId)` 임베드 가능(케이스에 이미 연결된 marker 만).
            **insight**: 한두 문장 요약(≤500). UI 타임라인 카드에 항상 표시.
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201", description = "생성됨",
            content = [Content(examples = [ExampleObject(value = """
                {"step":{"id":12,"errorCaseId":15,"authorUserId":777,"orderIndex":0,"title":"useEffect cleanup 추가","status":"FAILURE","attemptType":"CODE_CHANGE","body":null,"insight":"cleanup 만으로는 캐시까지 갱신되진 않음","createdAt":"...","updatedAt":"..."},"caseStatus":"IN_PROGRESS","suggestResolve":false}
            """)])]
        ),
        ApiResponse(responseCode = "400", description = "검증 실패", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 쓰기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Valid @RequestBody request: CreateStepRequest,
    ): CreateStepResponse {
        val r = try {
            createStepUseCase.invoke(
                CreateStepCommand(
                    errorCaseId = caseId,
                    authorUserId = userId,
                    title = request.title,
                    status = request.status,
                    attemptType = request.attemptType,
                    body = request.body,
                    insight = request.insight,
                )
            )
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return CreateStepResponse(
            step = StepResponse.from(r.step),
            caseStatus = r.caseStatus,
            suggestResolve = r.suggestResolve,
        )
    }

    @Operation(summary = "케이스의 step 목록", description = "orderIndex ASC, id ASC. 케이스 읽기 권한 필요.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @GetMapping
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
    ): List<StepResponse> {
        val steps = try {
            listStepsUseCase.invoke(caseId, userId)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return steps.map { StepResponse.from(it) }
    }

    @Operation(summary = "Step 부분 수정", description = "작성자 본인만. null/미포함=유지. attemptType 비우려면 clearAttemptType=true.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정됨"),
        ApiResponse(responseCode = "400", description = "검증 실패", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "작성자 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "step 없음", content = [Content()]),
    )
    @PatchMapping("/{stepId}")
    fun update(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "step ID") @PathVariable stepId: Long,
        @Valid @RequestBody request: UpdateStepRequest,
    ): UpdateStepResponse {
        val r = try {
            updateStepUseCase.invoke(
                UpdateStepCommand(
                    stepId = stepId,
                    requesterUserId = userId,
                    title = request.title,
                    status = request.status,
                    attemptType = request.attemptType,
                    clearAttemptType = request.clearAttemptType,
                    body = request.body,
                    insight = request.insight,
                )
            )
        } catch (e: StepNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: StepAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return UpdateStepResponse(
            step = StepResponse.from(r.step),
            caseStatus = r.caseStatus,
            suggestResolve = r.suggestResolve,
        )
    }

    @Operation(
        summary = "Step 삭제",
        description = "작성자 본인만. **참조하는 solution 이 있으면 409**(사용자가 solution 먼저 정리)."
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "작성자 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "step 없음", content = [Content()]),
        ApiResponse(responseCode = "409", description = "solution 이 이 step 을 참조 중", content = [Content()]),
    )
    @DeleteMapping("/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "step ID") @PathVariable stepId: Long,
    ) {
        try {
            deleteStepUseCase.invoke(stepId, userId)
        } catch (e: StepNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: StepAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: StepDeleteConflictException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
        }
    }
}
