package org.studieojavry.coreapi.errorcase.step.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus


@Schema(description = "Step 생성 요청. body 는 자유 마크다운(비워도 됨). insight 는 한두 문장 요약. attemptType 은 자유 String — system 카탈로그 값 또는 사용자 커스텀.")
data class CreateStepRequest(
    @field:Schema(description = "타임라인 카드 헤드라인", example = "useEffect cleanup 추가", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    @field:NotBlank @field:Size(max = 200)
    val title: String,

    @field:Schema(description = "결과 상태. RESOLVED/IN_PROGRESS/FAILED.", example = "FAILED", requiredMode = Schema.RequiredMode.REQUIRED)
    val status: StepStatus,

    @field:Schema(
        description = "시도 분류 (선택). 자유 String — `GET /step-attempt-types` 응답의 system/custom 값 또는 새 값. 새 값이면 자동으로 본인 카탈로그에 등록됨.",
        example = "CODE_CHANGE",
        maxLength = 64,
    )
    @field:Size(max = 64)
    val attemptType: String? = null,

    @field:Schema(description = "자유 마크다운 본문. `@snippet(markerId)`/`@attach(markerId)` 임베드 가능. 비어도 OK.")
    val body: String? = null,

    @field:Schema(description = "한두 문장 요약(≤500). RESOLVED=학습 / FAILED=원인 권장.", maxLength = 500)
    @field:Size(max = 500)
    val insight: String? = null,
)

@Schema(description = "Step 부분 수정. null/미포함=유지. attemptType 비우려면 clearAttemptType=true.")
data class UpdateStepRequest(
    @field:Schema(description = "타임라인 카드 헤드라인(보낼 경우 ≤200). null=유지.", example = "useEffect cleanup 추가(v2)", maxLength = 200)
    @field:Size(max = 200)
    val title: String? = null,

    @field:Schema(description = "결과 상태. null=유지.", example = "RESOLVED")
    val status: StepStatus? = null,

    @field:Schema(
        description = "시도 분류(자유 String). null=유지. *명시적으로 비우려면 clearAttemptType=true 사용*. 새 값이면 자동 카탈로그 등록.",
        example = "CODE_CHANGE",
        maxLength = 64,
    )
    @field:Size(max = 64)
    val attemptType: String? = null,

    @field:Schema(description = "true 면 attemptType 을 null 로 명시적으로 비움. 기본 false.", example = "false")
    val clearAttemptType: Boolean = false,

    @field:Schema(description = "자유 마크다운 본문(`@snippet`/`@attach` 임베드 가능). null=유지.")
    val body: String? = null,

    @field:Schema(description = "한두 문장 요약(≤500). null=유지.", maxLength = 500)
    @field:Size(max = 500)
    val insight: String? = null,
)
