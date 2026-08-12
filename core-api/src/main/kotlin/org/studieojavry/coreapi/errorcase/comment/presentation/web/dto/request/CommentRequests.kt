package org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.SuggestionSourceType
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.SuggestionStatus

@Schema(description = "댓글 생성 요청. 인용 없으면 quote* 모두 null/NONE. 답글은 parentCommentId 지정(서버가 depth=1 강제 redirect).")
data class CreateCommentRequest(
    @field:Schema(description = "답글 대상 댓글 id. null=top-level. 답글의 답글이면 서버가 top-level 로 자동 redirect.", example = "101")
    val parentCommentId: Long? = null,

    @field:Schema(description = "본문(마크다운). `@사용자`, `@snippet(id)`, ```code``` 가능.", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 5000)
    @field:NotBlank @field:Size(max = 5000)
    val body: String,

    @field:Schema(description = "인용 종류 (3종). NONE/CASE_BODY/STEP", example = "STEP")
    val quoteSourceKind: QuoteSourceKind = QuoteSourceKind.NONE,

    @field:Schema(description = "STEP → step id. NONE·CASE_BODY 면 null.", example = "3")
    val quoteSourceId: Long? = null,

    @field:Schema(description = "인용된 텍스트(서버가 최대 500자로 자름).", maxLength = 500)
    val quoteSnapshot: String? = null,

    @field:Valid
    @field:Schema(
        description = """
            GitHub 스타일 Diff 제안 (선택). 코드블럭 gutter 드래그 → 라인 범위 + after 코드.
            첨부 시 자동으로 status=PENDING. 케이스 owner 가 PATCH /suggestion/status 로 APPLIED/REJECTED 토글.
            sourceId 는 opaque — SNIPPET=markerId / MARKDOWN_CODE=step 내부 코드블럭 식별자(FE가 부여).
        """
    )
    val suggestion: SuggestionRequest? = null,
) {
    @Schema(description = "Diff 제안 입력.")
    data class SuggestionRequest(
        @field:Schema(description = "코드 출처 종류", example = "SNIPPET", requiredMode = Schema.RequiredMode.REQUIRED)
        val sourceType: SuggestionSourceType,

        @field:NotBlank @field:Size(max = 128)
        @field:Schema(description = "코드 출처 식별자(opaque)", example = "snippet-a1b2c3d4", requiredMode = Schema.RequiredMode.REQUIRED)
        val sourceId: String,

        @field:Min(1)
        @field:Schema(description = "시작 라인(1-base)", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        val startLine: Int,

        @field:Min(1)
        @field:Schema(description = "끝 라인(>= startLine)", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
        val endLine: Int,

        @field:Size(max = 10_000)
        @field:Schema(description = "선택 범위의 원본 코드(quoteSnapshot 과 동일 권장)", requiredMode = Schema.RequiredMode.REQUIRED)
        val oldCode: String,

        @field:Size(max = 10_000)
        @field:Schema(description = "제안 코드(빈 문자열이면 삭제 제안)", requiredMode = Schema.RequiredMode.REQUIRED)
        val newCode: String,
    )
}

@Schema(description = "댓글 본문 수정. body 만 변경. 작성자 본인만.")
data class UpdateCommentRequest(
    @field:NotBlank @field:Size(max = 5000)
    val body: String,
)

@Schema(description = "이모지 리액션 요청.")
data class AddReactionRequest(
    @field:Schema(description = "이모지(예: 👍 🎯 👀 ❤️ 🚀)", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 16)
    @field:NotBlank @field:Size(max = 16)
    val emoji: String,
)

@Schema(description = "Diff 제안 상태 변경 요청 — 케이스 owner 만.")
data class UpdateSuggestionStatusRequest(
    @field:Schema(description = "변경할 상태", example = "APPLIED", requiredMode = Schema.RequiredMode.REQUIRED)
    val status: SuggestionStatus,
)
