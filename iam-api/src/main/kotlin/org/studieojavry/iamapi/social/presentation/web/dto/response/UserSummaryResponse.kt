package org.studieojavry.iamapi.social.presentation.web.dto.response

data class UserSummaryResponse(
    val userId: Long,
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
    /** 인증된 viewer 가 이 사용자를 팔로우 중인지. 비로그인이면 false → FE 는 전부 'Follow' 로 표시. */
    val isFollowing: Boolean = false,
    /** 이 사용자가 viewer 본인인지. 본인 행은 FE 에서 팔로우 버튼을 숨긴다. */
    val isSelf: Boolean = false
)
