package org.studieojavry.coreapi.errorcase.snippet.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "미연결(pending) 스니펫 1건 — 작성 화면 '추가한 스니펫' 트레이용. 스니펫은 DB 텍스트라 code 를 직접 담는다.")
data class MyPendingSnippetResponse(
    val markerId: String,
    @Schema(description = "본문 임베드 토큰", example = "@snippet(7a7d35e9)")
    val embedToken: String,
    val title: String,
    val language: String,
    val filePathOrClass: String?,
    val lineRange: String?,
    val caption: String?,
    val code: String,
    val uploadedAt: Instant,
)
