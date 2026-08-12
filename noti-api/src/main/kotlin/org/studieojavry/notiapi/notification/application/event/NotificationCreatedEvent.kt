package org.studieojavry.notiapi.notification.application.event

import org.studieojavry.notiapi.notification.domain.Notification

/**
 * in-app 알림 row 가 *DB 에 commit 된 직후* SSE 로 push 하기 위한 도메인 이벤트.
 *
 * 발행 시점은 트랜잭션 내부지만, 리스너가 `@TransactionalEventListener(AFTER_COMMIT)` 로
 * 등록되어 있어 commit 이후에만 실제 SSE 전송이 일어난다. (commit 실패 시 push 안 됨.)
 */
data class NotificationCreatedEvent(
    val notification: Notification,
)
