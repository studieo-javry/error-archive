package org.studieojavry.notiapi.notification.infrastructure.sse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import tools.jackson.databind.ObjectMapper

/**
 * [RedisRealtimeChannel] 구독자 — 어느 노드에서 발행됐든 봉투를 받아 **이 노드의** 로컬 emitter 로
 * 전달한다([LocalSseDelivery]). 모든 noti 인스턴스가 각자 이 리스너를 돌리므로, 사용자가 붙어 있는
 * 노드가 메시지를 받아 전달하고 나머지 노드는 "해당 userId 로컬 emitter 없음"으로 조용히 무시한다.
 *
 * 리스너 등록은 [RedisRealtimeConfig] 의 RedisMessageListenerContainer 가 담당.
 * 역직렬화/전달 실패는 **삼킨다**(never throws) — 한 메시지 오류가 구독 전체를 끊지 않도록.
 */
class RedisRealtimeSubscriber(
    private val localDelivery: LocalSseDelivery,
    private val objectMapper: ObjectMapper,
) : MessageListener {

    private val log = KotlinLogging.logger {}

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val envelope = objectMapper.readValue(String(message.body, Charsets.UTF_8), RealtimeEnvelope::class.java)
            localDelivery.deliverToLocal(envelope.userId, envelope.eventId, envelope.data)
        } catch (e: Exception) {
            log.warn(e) { "[sse] failed to handle realtime channel message" }
        }
    }
}
