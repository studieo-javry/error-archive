package org.studieojavry.iamapi.workspace.presentation.web.dto.response

import java.time.Instant

/**
 * 멤버 목록의 항목. FE는 `userId`로 프로필 이동 (예: /users/{userId}).
 */
data class WorkspaceMemberResponse(
    val userId: Long,
    val displayName: String,
    val avatarUrl: String?,
    val role: String,
    val joinedAt: Instant,
    val profileHref: String
)