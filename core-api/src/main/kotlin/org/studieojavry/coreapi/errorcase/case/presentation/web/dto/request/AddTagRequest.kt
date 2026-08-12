package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "케이스 태그 추가 요청. trim·소문자 자동 정규화.")
data class AddTagRequest(
    @field:Schema(
        description = "추가할 태그(자유 문자열). 빈 문자열 거부, 최대 32자. 같은 케이스에 같은 태그가 이미 있으면 멱등 응답.",
        example = "k8s",
        requiredMode = Schema.RequiredMode.REQUIRED,
        maxLength = 32,
    )
    @field:NotBlank
    @field:Size(max = 32)
    val tag: String,
)
