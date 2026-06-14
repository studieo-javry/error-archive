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

    data class MentionEvent(
        val recipientUserIds: List<Long>,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )
}
