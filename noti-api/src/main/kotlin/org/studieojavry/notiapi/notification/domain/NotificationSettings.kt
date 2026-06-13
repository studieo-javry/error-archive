package org.studieojavry.notiapi.notification.domain

/**
 * 사용자 알림 설정. 채널(email / inApp) × 카테고리 매트릭스 + master switch.
 *
 * 도메인 객체이자 JSON 직렬화 표현 (Entity 의 jsonb 컬럼에 그대로 저장).
 * 새 카테고리 추가 시 default 값 + Patch 동일 필드 추가 + Response DTO 갱신.
 *
 * Security alerts는 항상 강제 on — 본 객체엔 보관하지 않고, 응답 DTO 가 강제로 채운다.
 * (계정 보안 이벤트는 끄지 못하게 하는 게 사용자 보호의 정공법.)
 */
data class NotificationSettings(
    val masterEnabled: Boolean = true,
    val email: Email = Email.defaults(),
    val inApp: InApp = InApp.defaults(),
) {
    /** 부분 갱신: null 미포함 필드는 유지(deep merge). */
    fun mergePatch(patch: Patch): NotificationSettings = NotificationSettings(
        masterEnabled = patch.masterEnabled ?: masterEnabled,
        email = patch.email?.let { email.merge(it) } ?: email,
        inApp = patch.inApp?.let { inApp.merge(it) } ?: inApp,
    )

    /**
     * `mentions` 는 "내가 @mention 된 경우" + "내 댓글에 reply 가 달린 경우" 둘 다를 의미.
     * 별도 토글이었던 `replies` 는 통합됨 — 사용자가 두 카테고리를 분리해 관리할 실익이 적고
     * 발송 로직에서도 이미 `mentions` 만 분기 기준이었음.
     */
    data class Email(
        val mentions: Boolean,
        val commentsOnMyContent: Boolean,
        val newFollowers: Boolean,
        val workspaceInvitations: Boolean,
        val weeklyDigest: Boolean,
        val productAnnouncements: Boolean,
    ) {
        fun merge(p: EmailPatch) = Email(
            mentions = p.mentions ?: mentions,
            commentsOnMyContent = p.commentsOnMyContent ?: commentsOnMyContent,
            newFollowers = p.newFollowers ?: newFollowers,
            workspaceInvitations = p.workspaceInvitations ?: workspaceInvitations,
            weeklyDigest = p.weeklyDigest ?: weeklyDigest,
            productAnnouncements = p.productAnnouncements ?: productAnnouncements,
        )
        companion object {
            fun defaults() = Email(
                mentions = true,
                commentsOnMyContent = true,
                newFollowers = false, workspaceInvitations = true,
                weeklyDigest = false, productAnnouncements = false,
            )
        }
    }

    data class InApp(
        val mentions: Boolean,
        val commentsOnMyContent: Boolean,
        val newFollowers: Boolean,
        val workspaceInvitations: Boolean,
    ) {
        fun merge(p: InAppPatch) = InApp(
            mentions = p.mentions ?: mentions,
            commentsOnMyContent = p.commentsOnMyContent ?: commentsOnMyContent,
            newFollowers = p.newFollowers ?: newFollowers,
            workspaceInvitations = p.workspaceInvitations ?: workspaceInvitations,
        )
        companion object {
            fun defaults() = InApp(
                mentions = true,
                commentsOnMyContent = true,
                newFollowers = true, workspaceInvitations = true,
            )
        }
    }

    data class Patch(
        val masterEnabled: Boolean? = null,
        val email: EmailPatch? = null,
        val inApp: InAppPatch? = null,
    )

    data class EmailPatch(
        val mentions: Boolean? = null,
        val commentsOnMyContent: Boolean? = null,
        val newFollowers: Boolean? = null,
        val workspaceInvitations: Boolean? = null,
        val weeklyDigest: Boolean? = null,
        val productAnnouncements: Boolean? = null,
    )

    data class InAppPatch(
        val mentions: Boolean? = null,
        val commentsOnMyContent: Boolean? = null,
        val newFollowers: Boolean? = null,
        val workspaceInvitations: Boolean? = null,
    )

    companion object {
        /** 신규 사용자 / null row 인 사용자에게 사용할 기본값. */
        fun defaults() = NotificationSettings()
    }
}
