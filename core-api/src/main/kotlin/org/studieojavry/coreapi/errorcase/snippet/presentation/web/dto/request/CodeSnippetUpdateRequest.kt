package org.studieojavry.coreapi.errorcase.snippet.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * 스니펫 내용 부분 수정(PATCH). 모든 필드 nullable — **보낸 필드만** 변경, null/미포함은 기존 값 유지.
 * code/language 는 보낼 경우 빈 문자열 금지(Size min=1 → 400). markerId·소유자·연결은 변경 불가.
 */
@Schema(description = "스니펫 내용 부분 수정(PATCH). null/미포함은 유지.")
data class CodeSnippetUpdateRequest(
    @field:Schema(description = "제목")
    val title: String?,

    @field:Schema(description = "언어(보낼 경우 비어있을 수 없음)", example = "kotlin")
    @field:Size(min = 1)
    val language: String?,

    @field:Schema(description = "파일 경로/클래스")
    val filePathOrClass: String?,

    @field:Schema(description = "라인 범위", example = "10-25")
    val lineRange: String?,

    @field:Schema(description = "부가 설명")
    val caption: String?,

    @field:Schema(description = "코드 본문(보낼 경우 비어있을 수 없음)", example = "fun calc(): Int = 99")
    @field:Size(min = 1)
    val code: String?
)