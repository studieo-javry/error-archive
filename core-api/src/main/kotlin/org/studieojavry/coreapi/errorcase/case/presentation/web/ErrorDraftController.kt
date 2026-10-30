package org.studieojavry.coreapi.errorcase.case.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.errorcase.case.application.usecase.SuggestErrorDraftUseCase
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.request.SuggestErrorDraftRequest
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.ErrorDraftResponse

@Tag(
    name = "error-draft",
    description = "에러 원문 붙여넣기 → New Entry 초안 자동 제안. 사실(예외/스택)은 정규식, 해석(제목·요약·태그·심각도)은 LLM.",
)
@RestController
@RequestMapping("/api/v1/error-cases")
class ErrorDraftController(
    private val suggestErrorDraftUseCase: SuggestErrorDraftUseCase,
) {

    @Operation(
        summary = "에러 초안 자동 제안",
        description = """
            붙여넣은 에러 원문에서 New Entry 폼 프리필용 초안을 만든다.

            **동작**
            - 서버가 먼저 민감정보(토큰/비밀번호/이메일 등)를 마스킹하고 길이를 절삭한 뒤 처리한다. 원문을 그대로 외부로 보내지 않는다.
            - **사실(exceptionClass/stacktrace)** 은 결정적 정규식이 담당하고, **해석(title/summary/tags/severity)** 만 LLM 이 제안한다.
            - **자동 저장 X.** 결과는 폼 프리필용이며 사용자가 검수·수정 후 `POST /error-cases` 로 확정한다(human-in-the-loop).

            **graceful degradation**
            - LLM 미설정/실패/타임아웃이면 정규식 기반 폴백 결과를 반환하고 `degraded=true` 로 표시한다(요청은 실패하지 않는다).
            - 이때 severity/summary 는 비어 있을 수 있다.
        """,
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200", description = "제안 생성(LLM 또는 폴백)",
            content = [Content(examples = [ExampleObject(value = """
                {"title":"NullPointerException: coupon is null","exceptionClass":"java.lang.NullPointerException","summary":"OrderService.calculateTotal 에서 coupon 이 null 이라 합계 계산 실패","tags":["nullpointerexception","java","order"],"severity":{"name":"S2","code":2,"label":"Degraded"},"degraded":false}
            """)])],
        ),
        ApiResponse(responseCode = "400", description = "paste 누락/공백 또는 길이 초과", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "429", description = "호출 한도 초과(분당/일일 쿼터). Retry-After 헤더 참고", content = [Content()]),
    )
    @PostMapping("/draft-suggestion")
    fun suggest(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: SuggestErrorDraftRequest,
    ): ErrorDraftResponse {
        val result = suggestErrorDraftUseCase.suggest(
            SuggestErrorDraftUseCase.Command(
                paste = request.paste,
                workspaceTags = request.workspaceTags,
                userId = userId,
            ),
        )
        return ErrorDraftResponse.from(result)
    }
}
