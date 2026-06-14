package org.studieojavry.iamapi.auth.application.command

import org.studieojavry.iamapi.auth.domain.model.vo.Theme

/**
 * Patch-style: null = "변경하지 않음".
 *
 * - `bio` 등 단순 *값* 필드는 `clear*` 플래그로 명시 비우기.
 * - **`avatarUrl` 은 *set 만 지원*** — 비우려면 `DELETE /users/me/avatar` (파일 자원 라이프사이클이 별도).
 *   외부 호스팅 URL 을 직접 박는 케이스만 여기서 처리.
 */
data class UpdateMyProfileCommand(
    val userId: Long,
    val displayName: String?,
    val avatarUrl: String?,
    val bio: String?,
    val clearBio: Boolean = false,

    // ── Preferences (Phase 1) ──
    val language: String? = null,
    val clearLanguage: Boolean = false,
    val timezone: String? = null,
    val clearTimezone: Boolean = false,
    val theme: Theme? = null,
    val defaultWorkspaceId: Long? = null,
    val clearDefaultWorkspace: Boolean = false,
)
