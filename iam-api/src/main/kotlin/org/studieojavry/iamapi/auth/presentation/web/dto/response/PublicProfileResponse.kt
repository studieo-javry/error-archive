package org.studieojavry.iamapi.auth.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "다른 사용자도 볼 수 있는 공개 프로필. 비공개 필드(email/preferences/탈퇴시각 등) 미노출.")
data class PublicProfileResponse(
    val userId: Long,
    @field:Schema(description = "GitHub login 매핑된 unique handle. MVP 정책: 불변.")
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    @field:Schema(description = "ACTIVE / PENDING_DELETION / SUSPENDED / DELETED")
    val status: String,
    @field:Schema(description = "탈퇴 확정 사용자 여부. 프런트가 '탈퇴한 사용자' 로 표시")
    val isDeleted: Boolean,
)