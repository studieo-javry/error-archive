package org.studieojavry.notiapi.notification.infrastructure.sse

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.sse.RealtimePublisherPort
import org.studieojavry.notiapi.notification.domain.Notification

/**
 * `noti.realtime.backend=local` 백엔드 — **단일 인스턴스**용 직결 publisher.
 *
 * 발생 노드 == 연결 노드 라는 가정에서 Redis 없이 곧바로 로컬 emitter 로 전달한다(MVP1/test).
 * 멀티 인스턴스에서는 [RedisRealtimePublisher] 가 기본값으로 대체한다.
 */
@Component
@ConditionalOnProperty(name = ["noti.realtime.backend"], havingValue = "local")
class LocalRealtimePublisher(
    private val broker: NotificationSseBroker,
) : RealtimePublisherPort {

    override fun publishToUser(userId: Long, notification: Notification) {
        val payload = broker.buildWirePayload(notification)
        broker.deliverToLocal(userId, payload.eventId, payload.json)
    }
}
