package org.studieojavry.iamapi.auth.presentation.web.dto.response

import java.time.Instant

data class MyProfileResponse(
    val userId: Long,
    val email: String?,
    /** GitHub login 매핑된 unique handle. MVP 정책: 불변. */
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    /** ACTIVE / PENDING_DELETION / SUSPENDED / DELETED */
    val status: String,
    /** status=PENDING_DELETION 일 때 grace 시작 시각. SPA 가 만료일 계산. */
    val pendingDeletionAt: Instant?,
    /** 계정 가입 시각. SPA 의 "Member since" 표시용. */
    val createdAt: Instant,

    // ── Preferences (Phase 1) ──
    /** BCP 47 language tag(예: "ko"). null=서버 기본 */
    val language: String?,
    /** IANA timezone(예: "Asia/Seoul"). null=서버 기본 */
    val timezone: String?,
    /** UI 테마. SYSTEM 은 OS 설정 따름 */
    val theme: String,
    /** 로그인 후 기본 진입 워크스페이스. null=개인 홈 */
    val defaultWorkspaceId: Long?,
)
