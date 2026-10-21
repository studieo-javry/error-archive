package org.studieojavry.coreapi.demo.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "게스트 사용 가이드 대상 — 샘플 케이스와 그 공개 아티클")
data class DemoGuideResponse(
    @field:Schema(description = "가이드가 따라갈 데모 워크스페이스 샘플 케이스 id(야간 리셋마다 바뀜)", example = "184")
    val caseId: Long,
    @field:Schema(description = "가이드 4단계에서 열 공개 아티클 slug — 공개 페이지는 /p/{slug}", example = "demo-hikaricp-503")
    val articleSlug: String,
)
