package org.studieojavry.iamapi.auth.application.command

import org.studieojavry.iamapi.auth.domain.model.vo.Theme

/**
 * Patch-style: null = "변경하지 않음".
 * bio/avatarUrl 은 명시적으로 비우려면 clear* 플래그. preferences(language/timezone/theme/defaultWorkspaceId)
 * 도 같은 정책 — null=유지, clear* 로 명시 비우기, theme 은 enum 이라 항상 한 값.
 */
data class UpdateMyProfileCommand(
    val userId: Long,
    val displayName: String?,
    val avatarUrl: String?,
    val bio: String?,
    val clearAvatar: Boolean = false,
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
