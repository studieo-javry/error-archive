package org.studieojavry.iamapi.auth.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "사용자 검색 결과 1건 (멘션 자동완성용 — 공개 정보만).")
data class UserSearchResponse(
    val userId: Long,
    @field:Schema(description = "GitHub login 매핑된 unique handle. MVP 정책: 불변.")
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
)