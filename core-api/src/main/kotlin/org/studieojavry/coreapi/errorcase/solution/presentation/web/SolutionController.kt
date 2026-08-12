package org.studieojavry.coreapi.errorcase.solution.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseAccessDeniedException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.solution.application.command.CreateSolutionCommand
import org.studieojavry.coreapi.errorcase.solution.application.usecase.CreateSolutionUseCase
import org.studieojavry.coreapi.errorcase.solution.application.usecase.DeleteSolutionUseCase
import org.studieojavry.coreapi.errorcase.solution.application.usecase.ListSolutionsUseCase
import org.studieojavry.coreapi.errorcase.solution.application.usecase.SolutionAccessDeniedException
import org.studieojavry.coreapi.errorcase.solution.application.usecase.SolutionInvalidException
import org.studieojavry.coreapi.errorcase.solution.application.usecase.SolutionNotFoundException
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.solution.presentation.web.dto.request.CreateSolutionRequest
import org.studieojavry.coreapi.errorcase.solution.presentation.web.dto.response.SolutionResponse


@Tag(
    name = "error-case-solutions",
    description = "에러케이스에서 step 들을 묶어 '최종 해결 방법'으로 등록. 한 케이스에 N개 solution 가능."
)
@RestController
@RequestMapping("/api/v1/error-cases/{caseId}/solutions")
class SolutionController(
    private val createSolutionUseCase: CreateSolutionUseCase,
    private val listSolutionsUseCase: ListSolutionsUseCase,
    private val deleteSolutionUseCase: DeleteSolutionUseCase,
) {

    @Operation(
        summary = "Solution 추가",
        description = """
            케이스의 step 중 일부를 묶어 한 문장 제목을 단다. 케이스 쓰기 권한 필요.
            stepIds 는 **모두 같은 케이스의 step** 이어야 하고(타 케이스 step 거부), 빈 배열·중복 금지.
            stepIds 의 순서가 보존된다(사용자가 선택한 순서대로 표시).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성됨"),
        ApiResponse(responseCode = "400", description = "검증 실패(빈 stepIds / 타 케이스 step / 중복)", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 쓰기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Valid @RequestBody request: CreateSolutionRequest,
    ): SolutionResponse {
        val s = try {
            createSolutionUseCase.invoke(
                CreateSolutionCommand(
                    errorCaseId = caseId,
                    authorUserId = userId,
                    title = request.title,
                    stepIds = request.stepIds,
                )
            )
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: SolutionInvalidException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return SolutionResponse.from(s)
    }

    @Operation(summary = "케이스의 solution 목록", description = "createdAt ASC. 케이스 읽기 권한 필요.")
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
    ): List<SolutionResponse> {
        val all = try {
            listSolutionsUseCase.invoke(caseId, userId)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return all.map { SolutionResponse.from(it) }
    }

    @Operation(summary = "Solution 삭제", description = "작성자 본인 또는 케이스 소유자.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "작성자/케이스 소유자 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "solution 없음", content = [Content()]),
    )
    @DeleteMapping("/{solutionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "solution ID") @PathVariable solutionId: Long,
    ) {
        try {
            deleteSolutionUseCase.invoke(solutionId, userId)
        } catch (e: SolutionNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: SolutionAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
    }
}
