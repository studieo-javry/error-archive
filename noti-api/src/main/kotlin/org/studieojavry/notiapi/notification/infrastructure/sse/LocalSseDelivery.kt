package org.studieojavry.notiapi.notification.infrastructure.sse

/**
 * **이 노드의 로컬 SseEmitter** 로 알림을 전달하는 최소 seam.
 *
 * [NotificationSseBroker] 가 구현하고, 로컬 직결([LocalRealtimePublisher]) 및 Redis 구독
 * ([RedisRealtimeSubscriber]) 양쪽이 공통으로 호출한다. seam 으로 분리해 Redis fan-out 경로를
 * 실제 emitter 없이 단위 테스트할 수 있게 한다(가짜 sink 주입).
 */
interface LocalSseDelivery {
    /** `userId` 에 연결된 *이 JVM* 의 emitter 들에 SSE 이벤트(event id + JSON data)를 전송. */
    fun deliverToLocal(userId: Long, eventId: String, json: String)
}
