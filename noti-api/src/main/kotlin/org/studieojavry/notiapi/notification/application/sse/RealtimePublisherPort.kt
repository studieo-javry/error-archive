package org.studieojavry.notiapi.notification.application.sse

import org.studieojavry.notiapi.notification.domain.Notification

/**
 * 실시간 알림을 *연결된 사용자에게* push 하는 port.
 *
 * MVP1: in-memory SseEmitter 어댑터 (단일 인스턴스 한정).
 * 향후 멀티 인스턴스: Redis pub/sub 어댑터로 교체 — 같은 port 유지하면 됨.
 */
interface RealtimePublisherPort {
    fun publishToUser(userId: Long, notification: Notification)
}
