package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "에러 원문 붙여넣기 → New Entry 초안 자동 제안 요청")
data class SuggestErrorDraftRequest(
    @field:Schema(
        description = "에러 원문(스택트레이스/로그). 서버가 마스킹·절삭 후 처리한다.",
        example = "java.lang.NullPointerException: coupon is null\n\tat com.shop.OrderService.calculateTotal(OrderService.java:42)",
        maxLength = 20000,
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotBlank
    @field:Size(max = 20000)
    val paste: String,

    @field:Schema(
        description = "이 워크스페이스에서 자주 쓰는 태그(신규 태그 남발 방지용 어휘 힌트). 선택.",
        example = "[\"java\", \"prod\", \"order\"]",
    )
    val workspaceTags: List<String> = emptyList(),
)
