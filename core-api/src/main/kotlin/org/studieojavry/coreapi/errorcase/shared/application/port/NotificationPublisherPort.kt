package org.studieojavry.coreapi.errorcase.shared.application.port

import org.springframework.stereotype.Repository

/**
 * 알림 발사 — core-api → noti-api 의 cross-service publisher.
 *
 * 현재 구현은 **동기 HTTP** (`POST /internal/notifications/mentions`).
 * 추후 Kafka producer 로 교체 시 *Port 만 유지* 하면 호출자 변경 없음.
 *
 * 호출 실패는 *조용히 삼킴* — 알림 누락이 *댓글 작성을 막아서는 안 됨*.
 * (멘션 자체는 DB 에 적재되어 있어 *재처리* 가 가능.)
 */
@Repository
interface NotificationPublisherPort {
    fun publishMentions(event: MentionEvent)

    /**
     * Reply 알림 — 부모 댓글 author 에게 *내 댓글에 답글이 달렸음* 전달.
     * default no-op — legacy HTTP adapter 가 reply endpoint 미구현 시에도 컴파일 통과.
     * 운영(kafka mode)은 [OutboxNotificationPublisherAdapter] 가 override.
     */
    fun publishReplies(event: ReplyEvent) {}

    /**
     * 내 글에 *최상위* 댓글 — ErrorCase author 에게 알림. mention/reply 와 *별도 토글*.
     * 중복 회피는 호출자(`CreateCommentUseCase`)가 책임:
     *  - parentCommentId == null (답글 아님)
     *  - actor != owner
     *  - mention recipient 에 owner 포함 시 skip
     */
    fun publishCommentOnErrorCase(event: CommentOnErrorCaseEvent) {}

    data class MentionEvent(
        val recipientUserIds: List<Long>,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )

    data class ReplyEvent(
        val recipientUserId: Long,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val parentCommentId: Long,
        val snippet: String,
    )

    data class CommentOnErrorCaseEvent(
        val recipientUserId: Long,    // ErrorCase.ownerUserId
        val actorUserId: Long,        // 댓글 작성자
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )
}
