package org.studieojavry.notiapi.notification.infrastructure.sse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.studieojavry.notiapi.notification.domain.Notification
import org.studieojavry.notiapi.notification.domain.NotificationDeepLink
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * in-memory SSE broker — userId → 활성 SseEmitter 다중 (한 user 가 여러 탭/디바이스 열 수 있음).
 *
 * **SseEmitter 레지스트리는 본질적으로 로컬**이다 — emitter 는 그 HTTP 연결을 소유한 JVM 에서만
 * write 할 수 있다. 따라서 멀티 인스턴스에서 "인스턴스 A 에서 발생한 이벤트를 인스턴스 B 에 붙은
 * 사용자에게" 전달하려면 **노드 간 fan-out**(Redis pub/sub)으로 각 노드가 *자기* 로컬 emitter 에만
 * 쓰도록 해야 한다. 이 broker 는 그 **로컬 전달 엔진**([LocalSseDelivery] 구현)이고, 어느 백엔드든
 * (로컬 직결 / Redis 구독) 최종 전달은 여기로 모인다.
 *
 * - `local` 백엔드: [LocalRealtimePublisher] 가 같은 JVM 에서 바로 [deliverToLocal] 호출.
 * - `redis` 백엔드: [RedisRealtimePublisher] 가 채널에 발행 → 모든 노드의 [RedisRealtimeSubscriber] 가
 *   수신 → 각 노드가 자기 [deliverToLocal] 호출. (wire payload 는 [buildWirePayload] 한 곳에서 생성해
 *   로컬/크로스노드 전송 포맷이 동일하다.)
 *
 * 정리 정책:
 *  - emitter.send 실패 (네트워크 끊김) → onCompletion / onError 콜백이 unregister 호출
 *  - 30s heartbeat — 중간 프록시(nginx 등)가 idle 끊지 않게 + dead connection 빠른 정리
 */
@Component
class NotificationSseBroker(
    private val objectMapper: ObjectMapper,
) : LocalSseDelivery {

    private val log = KotlinLogging.logger {}
    private val emitters = ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>()

    /**
     * 사용자 구독 등록. timeout = 0 → 무한 (브라우저가 disconnect 처리).
     * 첫 응답으로 'connected' comment 1줄 보내 즉시 stream open 보장.
     */
    fun subscribe(userId: Long): SseEmitter {
        val emitter = SseEmitter(0L)
        val bucket = emitters.computeIfAbsent(userId) { CopyOnWriteArrayList() }
        bucket.add(emitter)

        emitter.onCompletion { unregister(userId, emitter, "completion") }
        emitter.onTimeout { unregister(userId, emitter, "timeout") }
        emitter.onError { ex -> unregister(userId, emitter, "error: ${ex.message}") }

        try {
            emitter.send(SseEmitter.event().comment("connected"))
        } catch (e: IOException) {
            unregister(userId, emitter, "initial-send-failed")
        }
        log.debug { "[sse] subscribed userId=$userId · activeForUser=${bucket.size}" }
        return emitter
    }

    /**
     * 알림 1건을 SSE wire payload(FE EventSource 가 받는 JSON + event id)로 직렬화.
     * 로컬/Redis 백엔드가 **동일한 포맷**을 쓰도록 생성 로직을 여기에 단일화한다.
     */
    fun buildWirePayload(notification: Notification): WirePayload {
        val payloadMap: Map<String, Any?> = runCatching {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(notification.payload, Map::class.java) as Map<String, Any?>
        }.getOrDefault(emptyMap())

        val dto = RealtimeNotificationDto(
            id = notification.id ?: -1L,
            type = notification.type.name,
            actorUserId = notification.actorUserId,
            payload = payloadMap,
            deepLink = NotificationDeepLink.from(notification.type, payloadMap),
            createdAt = notification.createdAt.toString(),
        )
        return WirePayload(eventId = notification.id?.toString() ?: "", json = objectMapper.writeValueAsString(dto))
    }

    /**
     * **이 노드의** userId 로컬 emitter 들에만 전송. 로컬 직결/Redis 구독 양쪽의 공통 종착점.
     * (다른 노드의 emitter 는 그 노드의 subscriber 가 각자 호출한다.)
     */
    override fun deliverToLocal(userId: Long, eventId: String, json: String) {
        val bucket = emitters[userId] ?: return
        if (bucket.isEmpty()) return

        val event = SseEmitter.event()
            .name("notification")
            .id(eventId)
            .data(json)

        val dead = mutableListOf<SseEmitter>()
        bucket.forEach { e ->
            try { e.send(event) } catch (ex: Exception) { dead.add(e) }
        }
        dead.forEach { unregister(userId, it, "send-failed") }
        log.debug { "[sse] delivered userId=$userId · sent=${bucket.size - dead.size} · failed=${dead.size}" }
    }

    /**
     * 30s 마다 모든 emitter 에 heartbeat comment. nginx default idle = 60s 이므로 그 전에 ping.
     * dead 인 connection 은 자연스레 onError → unregister.
     */
    @Scheduled(fixedDelay = 30_000)
    fun heartbeat() {
        emitters.forEach { (uid, bucket) ->
            val dead = mutableListOf<SseEmitter>()
            bucket.forEach { e ->
                try { e.send(SseEmitter.event().comment("hb")) } catch (ex: Exception) { dead.add(e) }
            }
            dead.forEach { unregister(uid, it, "hb-failed") }
        }
    }

    private fun unregister(userId: Long, emitter: SseEmitter, reason: String) {
        emitters[userId]?.let { bucket ->
            bucket.remove(emitter)
            if (bucket.isEmpty()) emitters.remove(userId)
        }
        log.debug { "[sse] unregistered userId=$userId · reason=$reason" }
    }

    /** SSE 전송용 직렬화 결과 — event id + data(JSON). */
    data class WirePayload(val eventId: String, val json: String)

    /** SSE wire format DTO. FE 의 EventSource 가 받는 JSON 모양. */
    private data class RealtimeNotificationDto(
        val id: Long,
        val type: String,
        val actorUserId: Long?,
        val payload: Map<String, Any?>,
        val deepLink: String?,
        val createdAt: String,
    )
}
