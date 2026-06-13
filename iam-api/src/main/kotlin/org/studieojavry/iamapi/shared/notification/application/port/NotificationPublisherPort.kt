package org.studieojavry.iamapi.shared.notification.application.port

import org.springframework.stereotype.Repository

/**
 * iam-api → noti-api 알림 발사 port. iam-api 의 outbox 인프라 (OutboxRelayer 등) 위에 push.
 * core-api 의 같은 이름 port 와 *독립* — iam-api 도메인 (social, workspace) 의 알림만 발사.
 *
 * default no-op — `noti.publisher.mode != kafka` 일 때 fallback adapter 또는 unconfigured 환경에서
 * 도메인 흐름이 발화 호출해도 무해.
 */
@Repository
interface NotificationPublisherPort {

    /** 누군가가 사용자를 팔로우 — recipientUserId = 팔로우 *당한* 사람. */
    fun publishNewFollower(event: NewFollowerEvent) {}

    /** 워크스페이스 초대 (in-app 만, email 채널은 기존 transactional invitation email 이 책임). */
    fun publishWorkspaceInvitation(event: WorkspaceInvitationEvent) {}

    data class NewFollowerEvent(
        val recipientUserId: Long,
        val followerUserId: Long,
    )

    data class WorkspaceInvitationEvent(
        val recipientUserId: Long,
        val workspaceId: Long,
        val workspaceName: String,
        val invitationId: Long,
        val invitedByUserId: Long,
    )
}
