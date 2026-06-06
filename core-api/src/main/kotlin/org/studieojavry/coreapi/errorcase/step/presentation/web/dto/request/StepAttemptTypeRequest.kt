package org.studieojavry.coreapi.errorcase.step.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "Step 시도 분류(`attemptType`) 카탈로그에 사용자 커스텀 값을 추가하는 요청. 멱등.")
data class StepAttemptTypeRequest(
    @field:Schema(
        description = "표시용 이름(원본 대소문자 유지). 1~64자. 동등성은 소문자 정규화로 판단.",
        example = "DB-Migration",
        requiredMode = Schema.RequiredMode.REQUIRED,
        maxLength = 64,
    )
    @field:NotBlank @field:Size(max = 64)
    val name: String,
)
