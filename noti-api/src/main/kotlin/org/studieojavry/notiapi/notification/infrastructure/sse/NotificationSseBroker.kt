package org.studieojavry.notiapi.notification.infrastructure.sse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.studieojavry.notiapi.notification.application.sse.RealtimePublisherPort
import org.studieojavry.notiapi.notification.domain.Notification
import org.studieojavry.notiapi.notification.domain.NotificationDeepLink
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * in-memory SSE broker — userId → 활성 SseEmitter 다중 (한 user 가 여러 탭/디바이스 열 수 있음).
 *
 * 단일 인스턴스 한정 — 멀티 인스턴스 환경에서는 user 가 인스턴스 A 에 붙어있는데
 * 이벤트가 인스턴스 B 에서 발행되면 못 받음. MVP1 은 noti-api 단일 노드 가정.
 * 후속 확장: Redis pub/sub 으로 노드 간 fan-out.
 *
 * 정리 정책:
 *  - emitter.send 실패 (네트워크 끊김) → onCompletion / onError 콜백이 unregister 호출
 *  - 30s heartbeat — 중간 프록시(nginx 등)가 idle 끊지 않게 + dead connection 빠른 정리
 */
@Component
class NotificationSseBroker(
    private val objectMapper: ObjectMapper,
) : RealtimePublisherPort {

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

    override fun publishToUser(userId: Long, notification: Notification) {
        val bucket = emitters[userId] ?: return
        if (bucket.isEmpty()) return

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
        val json = objectMapper.writeValueAsString(dto)
        val event = SseEmitter.event()
            .name("notification")
            .id(notification.id?.toString() ?: "")
            .data(json)

        val dead = mutableListOf<SseEmitter>()
        bucket.forEach { e ->
            try { e.send(event) } catch (ex: Exception) { dead.add(e) }
        }
        dead.forEach { unregister(userId, it, "send-failed") }
        log.debug { "[sse] published userId=$userId · sent=${bucket.size - dead.size} · failed=${dead.size}" }
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
