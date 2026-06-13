package org.studieojavry.notiapi.notification.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import org.studieojavry.notiapi.notification.domain.NotificationSettings

/**
 * 알림 설정 응답.
 * `securityAlerts` 는 **항상 true** — 보안 이벤트는 끌 수 없음.
 */
@Schema(description = "사용자 알림 설정. securityAlerts 는 양 채널 모두 강제 on (변경 불가).")
data class NotificationSettingsResponse(
    val masterEnabled: Boolean,
    val email: EmailChannelResponse,
    val inApp: InAppChannelResponse,
) {
    companion object {
        fun from(s: NotificationSettings) = NotificationSettingsResponse(
            masterEnabled = s.masterEnabled,
            email = EmailChannelResponse(
                mentions = s.email.mentions,
                commentsOnMyContent = s.email.commentsOnMyContent,
                newFollowers = s.email.newFollowers,
                workspaceInvitations = s.email.workspaceInvitations,
                weeklyDigest = s.email.weeklyDigest,
                productAnnouncements = s.email.productAnnouncements,
                securityAlerts = true,
            ),
            inApp = InAppChannelResponse(
                mentions = s.inApp.mentions,
                commentsOnMyContent = s.inApp.commentsOnMyContent,
                newFollowers = s.inApp.newFollowers,
                workspaceInvitations = s.inApp.workspaceInvitations,
                securityAlerts = true,
            ),
        )
    }
}

data class EmailChannelResponse(
    @field:Schema(description = "@mention 받은 경우 + 내 댓글에 reply 달린 경우. 두 케이스가 통합된 단일 토글.")
    val mentions: Boolean,
    @field:Schema(description = "내 글에 새 (최상위) 댓글이 달린 경우.")
    val commentsOnMyContent: Boolean,
    val newFollowers: Boolean,
    val workspaceInvitations: Boolean,
    val weeklyDigest: Boolean,
    val productAnnouncements: Boolean,
    @field:Schema(description = "보안 이벤트 알림. **항상 true** — 변경 불가.", example = "true")
    val securityAlerts: Boolean,
)

data class InAppChannelResponse(
    @field:Schema(description = "@mention 받은 경우 + 내 댓글에 reply 달린 경우. 두 케이스가 통합된 단일 토글.")
    val mentions: Boolean,
    @field:Schema(description = "내 글에 새 (최상위) 댓글이 달린 경우.")
    val commentsOnMyContent: Boolean,
    val newFollowers: Boolean,
    val workspaceInvitations: Boolean,
    @field:Schema(description = "보안 이벤트 알림. **항상 true** — 변경 불가.", example = "true")
    val securityAlerts: Boolean,
)