package org.studieojavry.coreapi.errorcase.snippet.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "스니펫 생성 요청")
data class CodeSnippetCreateRequest(
    @field:Schema(description = "표시용 제목(미지정 시 'untitled')", example = "OrderService.calc")
    val title: String?,

    @field:Schema(description = "언어 식별자(highlight 용)", example = "kotlin", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank
    val language: String,

    @field:Schema(description = "원본 파일 경로 또는 클래스", example = "src/main/kotlin/.../OrderService.kt")
    val filePathOrClass: String?,

    @field:Schema(description = "라인 범위", example = "10-20")
    val lineRange: String?,

    @field:Schema(description = "부가 설명")
    val caption: String?,

    @field:Schema(description = "코드 본문", example = "fun calc(): Int = 42", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank
    val code: String
)
