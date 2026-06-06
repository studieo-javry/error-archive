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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.step.application.usecase.AddStepAttemptTypeUseCase
import org.studieojavry.coreapi.errorcase.step.application.usecase.ListStepAttemptTypesUseCase
import org.studieojavry.coreapi.errorcase.step.application.usecase.RemoveStepAttemptTypeUseCase
import org.studieojavry.coreapi.errorcase.step.presentation.web.dto.request.StepAttemptTypeRequest
import org.studieojavry.coreapi.errorcase.step.presentation.web.dto.response.StepAttemptTypeResponse


@Tag(
    name = "step-attempt-types",
    description = """
        Step 의 `attemptType` 카탈로그. system 값(`CODE_CHANGE`/`CONFIG`/`DEPENDENCY`/`ENV`/`ROLLBACK`/`INVESTIGATION`/`OTHER`)은 모든 사용자에게 기본 제공.
        본인 커스텀 값은 따로 관리되며 다른 사용자에게는 보이지 않는다.

        **자동 등록**: step 생성/수정 시 attemptType 이 system/custom 어디에도 없으면 자동으로 본인 카탈로그에 멱등 add.
        명시적으로 추가/삭제하려면 본 컨트롤러의 POST/DELETE 사용.
    """
)
@RestController
@RequestMapping("/api/v1/step-attempt-types")
class StepAttemptTypeController(
    private val listUseCase: ListStepAttemptTypesUseCase,
    private val addUseCase: AddStepAttemptTypeUseCase,
    private val removeUseCase: RemoveStepAttemptTypeUseCase,
) {

    @Operation(
        summary = "Step 시도 분류 카탈로그 조회",
        description = "system(공통) + 본인 custom 을 한 배열로 반환. system 이 먼저, custom 은 추가 순(ASC)."
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200", description = "성공",
            content = [Content(examples = [ExampleObject(value = """
                [
                  {"name":"CODE_CHANGE","isSystem":true},
                  {"name":"CONFIG","isSystem":true},
                  {"name":"DB-Migration","isSystem":false}
                ]
            """)])]
        ),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
    ): List<StepAttemptTypeResponse> =
        listUseCase.invoke(userId).map { StepAttemptTypeResponse(name = it.name, isSystem = it.isSystem) }

    @Operation(
        summary = "Step 시도 분류 커스텀 추가(멱등)",
        description = """
            본인 카탈로그에 자유 String 추가. 이미 같은 정규화 키가 있으면 200 으로 그대로 반환(추가 X).
            **system 값과 같으면** 400 — system 은 이미 모두에게 제공되므로 별도 추가 불필요.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "추가됨(또는 이미 존재)"),
        ApiResponse(responseCode = "400", description = "검증 실패 / system 값과 충돌", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: StepAttemptTypeRequest,
    ): StepAttemptTypeResponse {
        val r = try {
            addUseCase.invoke(userId, request.name)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        if (r.alreadySystem) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "attemptType '${r.name}' is a system value; no custom add needed")
        }
        return StepAttemptTypeResponse(name = r.name, isSystem = false)
    }

    @Operation(
        summary = "Step 시도 분류 커스텀 삭제",
        description = """
            본인 카탈로그에서 단건 제거. **system 값은 거부(400)**.
            *이미 저장된 step 의 attemptType String 값은 유지* — 자동완성 후보에서만 사라짐.
            없는 값이면 404.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제됨"),
        ApiResponse(responseCode = "400", description = "system 값 / 빈 값", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "404", description = "본인 카탈로그에 없음", content = [Content()]),
    )
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "삭제할 커스텀 이름(대소문자 무시). URL 인코딩 필요.", example = "DB-Migration")
        @PathVariable name: String,
    ) {
        val r = try {
            removeUseCase.invoke(userId, name)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        if (!r.removed) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "custom attemptType not found: $name")
        }
    }
}
