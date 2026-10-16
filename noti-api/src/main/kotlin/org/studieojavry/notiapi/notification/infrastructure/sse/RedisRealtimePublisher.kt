package org.studieojavry.notiapi.notification.infrastructure.sse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.sse.RealtimePublisherPort
import org.studieojavry.notiapi.notification.domain.Notification
import tools.jackson.databind.ObjectMapper

/**
 * `noti.realtime.backend=redis` 백엔드(기본) — **노드 간 fan-out** publisher.
 *
 * 로컬 emitter 로 바로 쓰지 않고 [RedisRealtimeChannel] 에 봉투를 발행한다. 모든 노드의
 * [RedisRealtimeSubscriber] 가 이를 수신해 *자기* 로컬 emitter 로 전달하므로, 이벤트가 발생한
 * 노드와 사용자가 접속한 노드가 달라도 실시간 push 가 도달한다(= 멀티 인스턴스 해소).
 */
@Component
@ConditionalOnProperty(name = ["noti.realtime.backend"], havingValue = "redis", matchIfMissing = true)
class RedisRealtimePublisher(
    private val broker: NotificationSseBroker,
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : RealtimePublisherPort {

    private val log = KotlinLogging.logger {}

    override fun publishToUser(userId: Long, notification: Notification) {
        val payload = broker.buildWirePayload(notification)
        val envelope = RealtimeEnvelope(userId = userId, eventId = payload.eventId, data = payload.json)
        val message = objectMapper.writeValueAsString(envelope)
        redis.convertAndSend(RedisRealtimeChannel.NAME, message)
        log.debug { "[sse] published to redis channel userId=$userId eventId=${payload.eventId}" }
    }
}
