package org.studieojavry.notiapi.notification.infrastructure.sse

import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.studieojavry.notiapi.notification.application.event.NotificationCreatedEvent
import org.studieojavry.notiapi.notification.application.sse.RealtimePublisherPort

/**
 * `InAppMentionWriter` 가 발행한 NotificationCreatedEvent 를 *DB 커밋 직후* 받아
 * 실시간 SSE push 로 dispatch.
 *
 * AFTER_COMMIT 인 이유: SSE 받은 FE 가 즉시 `GET /notifications` 로 cross-check 했을 때
 * 새 row 가 보장되어야 함. BEFORE_COMMIT 이면 commit 실패 시 *유령 알림* 가능성.
 */
@Component
class NotificationEventDispatcher(
    private val realtime: RealtimePublisherPort,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: NotificationCreatedEvent) {
        realtime.publishToUser(event.notification.recipientUserId, event.notification)
    }
}
