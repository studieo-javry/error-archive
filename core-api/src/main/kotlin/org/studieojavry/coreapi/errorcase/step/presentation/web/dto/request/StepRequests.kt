package org.studieojavry.coreapi.errorcase.step.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.AttemptType
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus


@Schema(description = "Step 생성 요청. body 는 자유 마크다운(비워도 됨). insight 는 한두 문장 요약.")
data class CreateStepRequest(
    @field:Schema(description = "타임라인 카드 헤드라인", example = "useEffect cleanup 추가", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    @field:NotBlank @field:Size(max = 200)
    val title: String,

    @field:Schema(description = "결과 상태", example = "FAILURE", requiredMode = Schema.RequiredMode.REQUIRED)
    val status: StepStatus,

    @field:Schema(description = "시도 분류(선택)", example = "CODE_CHANGE")
    val attemptType: AttemptType? = null,

    @field:Schema(description = "자유 마크다운 본문. `@snippet(markerId)`/`@attach(markerId)` 임베드 가능. 비어도 OK.")
    val body: String? = null,

    @field:Schema(description = "한두 문장 요약(≤500). SUCCESS=학습 / FAILURE=원인 권장.", maxLength = 500)
    @field:Size(max = 500)
    val insight: String? = null,
)

@Schema(description = "Step 부분 수정. null/미포함=유지. attemptType 비우려면 clearAttemptType=true.")
data class UpdateStepRequest(
    @field:Size(max = 200)
    val title: String? = null,
    val status: StepStatus? = null,
    val attemptType: AttemptType? = null,
    @field:Schema(description = "true 면 attemptType 을 null 로 명시적으로 비움")
    val clearAttemptType: Boolean = false,
    val body: String? = null,
    @field:Size(max = 500)
    val insight: String? = null,
)
