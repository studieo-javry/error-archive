package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import org.studieojavry.coreapi.errorcase.case.application.usecase.SuggestErrorDraftUseCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Severity

@Schema(description = "에러 자동 구조화 초안 — New Entry 폼 프리필용(자동 저장 X, 사용자 검수 필요)")
data class ErrorDraftResponse(
    @field:Schema(description = "제안 제목(≤120자)", example = "NullPointerException: coupon is null")
    val title: String,

    @field:Schema(description = "추출한 예외 클래스(없으면 null). 사실은 정규식 우선.", example = "java.lang.NullPointerException", nullable = true)
    val exceptionClass: String?,

    @field:Schema(description = "한 줄 요약(≤200자, 없으면 null)", nullable = true)
    val summary: String?,

    @field:Schema(description = "제안 태그(소문자·중복제거·최대 5개)", example = "[\"nullpointerexception\", \"java\", \"order\"]")
    val tags: List<String>,

    @field:Schema(description = "제안 심각도(판단 불가 시 null)", nullable = true)
    val severity: SeverityView?,

    @field:Schema(
        description = "true = LLM 미사용/실패 → 정규식 기반 폴백 결과(심각도·요약은 비어 있을 수 있음). FE 는 '규칙 기반 초안' 뱃지 노출 등에 활용.",
        example = "false",
    )
    val degraded: Boolean,
) {
    @Schema(description = "심각도 뷰(코드·라벨 포함)")
    data class SeverityView(
        @field:Schema(description = "심각도 코드명", example = "S2") val name: String,
        @field:Schema(description = "숫자 코드(1=최상위 장애)", example = "2") val code: Int,
        @field:Schema(description = "영문 라벨", example = "Degraded") val label: String,
    )

    companion object {
        fun from(result: SuggestErrorDraftUseCase.Result): ErrorDraftResponse =
            ErrorDraftResponse(
                title = result.title,
                exceptionClass = result.exceptionClass,
                summary = result.summary,
                tags = result.tags,
                severity = result.severity?.toView(),
                degraded = result.degraded,
            )

        private fun Severity.toView() = SeverityView(name = name, code = code, label = label)
    }
}
