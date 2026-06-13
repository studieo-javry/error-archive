package org.studieojavry.notiapi.notification.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import org.studieojavry.notiapi.notification.domain.NotificationSettings

/**
 * 알림 설정 부분 갱신. null/미포함 = 유지.
 * **Security alerts 는 변경 불가** — 본 페이로드에 포함될 수 없음.
 */
@Schema(description = "알림 설정 부분 갱신. 미포함 필드는 유지.")
data class UpdateNotificationSettingsRequest(
    @field:Schema(description = "전체 알림 master switch. false 면 카테고리 설정과 무관하게 모든 알림 차단.")
    val masterEnabled: Boolean? = null,
    val email: EmailPatchRequest? = null,
    val inApp: InAppPatchRequest? = null,
) {
    fun toPatch(): NotificationSettings.Patch = NotificationSettings.Patch(
        masterEnabled = masterEnabled,
        email = email?.toPatch(),
        inApp = inApp?.toPatch(),
    )
}

@Schema(description = "Email 채널 카테고리 부분 갱신.")
data class EmailPatchRequest(
    @field:Schema(description = "@mention 받은 경우 + 내 댓글에 reply 달린 경우 통합 토글.")
    val mentions: Boolean? = null,
    @field:Schema(description = "내 글에 새 (최상위) 댓글이 달린 경우.")
    val commentsOnMyContent: Boolean? = null,
    val newFollowers: Boolean? = null,
    val workspaceInvitations: Boolean? = null,
    val weeklyDigest: Boolean? = null,
    val productAnnouncements: Boolean? = null,
) {
    fun toPatch() = NotificationSettings.EmailPatch(
        mentions = mentions,
        commentsOnMyContent = commentsOnMyContent,
        newFollowers = newFollowers, workspaceInvitations = workspaceInvitations,
        weeklyDigest = weeklyDigest, productAnnouncements = productAnnouncements,
    )
}

@Schema(description = "In-app 채널 카테고리 부분 갱신.")
data class InAppPatchRequest(
    @field:Schema(description = "@mention 받은 경우 + 내 댓글에 reply 달린 경우 통합 토글.")
    val mentions: Boolean? = null,
    @field:Schema(description = "내 글에 새 (최상위) 댓글이 달린 경우.")
    val commentsOnMyContent: Boolean? = null,
    val newFollowers: Boolean? = null,
    val workspaceInvitations: Boolean? = null,
) {
    fun toPatch() = NotificationSettings.InAppPatch(
        mentions = mentions,
        commentsOnMyContent = commentsOnMyContent,
        newFollowers = newFollowers, workspaceInvitations = workspaceInvitations,
    )
}