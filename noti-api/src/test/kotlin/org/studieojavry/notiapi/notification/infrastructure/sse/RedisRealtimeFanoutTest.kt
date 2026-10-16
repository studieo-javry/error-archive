package org.studieojavry.notiapi.notification.infrastructure.sse

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.studieojavry.notiapi.notification.domain.Notification
import org.studieojavry.notiapi.notification.domain.NotificationType
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 실시간 SSE **노드 간 fan-out**(Redis pub/sub) 왕복 검증.
 *
 * 실제 redis-server 를 ephemeral 포트에 띄우고, [RedisRealtimePublisher] 가 발행한 알림이
 * 실 Redis 를 거쳐 [RedisRealtimeSubscriber] 에 도달해 **로컬 전달([LocalSseDelivery])** 로
 * 이어지는지 확인한다. 이것이 "발생 노드 ≠ 접속 노드"여도 push 가 도달하는 핵심 경로다.
 *
 * (Spring 컨텍스트 없이 pub/sub 배선만 직접 구성 — DB/JWT 등 env 불요. 로컬 전달은 실제 emitter 대신
 *  가짜 sink 로 캡처해 포맷·도달을 단언한다.)
 */
class RedisRealtimeFanoutTest {

    @Test
    @DisplayName("publisher → 실 Redis 채널 → subscriber → 로컬 전달(userId/eventId/data 왕복)")
    fun redis_pubsub_delivers_cross_node() {
        val objectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val broker = NotificationSseBroker(objectMapper)

        // 수신 측(다른 노드를 모사): 구독자 + 로컬 전달 가짜 sink.
        val received = LinkedBlockingQueue<Triple<Long, String, String>>()
        val sink = object : LocalSseDelivery {
            override fun deliverToLocal(userId: Long, eventId: String, json: String) {
                received.add(Triple(userId, eventId, json))
            }
        }
        val subscriber = RedisRealtimeSubscriber(sink, objectMapper)
        listenerContainer.addMessageListener(subscriber, ChannelTopic(RedisRealtimeChannel.NAME))

        // 발행 측(한 노드를 모사).
        val publisher = RedisRealtimePublisher(broker, template, objectMapper)
        val notification = Notification.rehydrate(
            id = 45L,
            recipientUserId = 777L,
            type = NotificationType.NEW_FOLLOWER,
            actorUserId = 9L,
            payload = "{}",
            readAt = null,
            createdAt = Instant.now(),
        )
        val expected = broker.buildWirePayload(notification)

        // 구독 활성화는 비동기 → 수신될 때까지 재발행(최대 ~5s). pub/sub 은 미저장이라 구독 전 발행은 유실.
        val deadline = System.currentTimeMillis() + 5_000
        var hit: Triple<Long, String, String>? = null
        while (System.currentTimeMillis() < deadline && hit == null) {
            publisher.publishToUser(777L, notification)
            hit = received.poll(200, TimeUnit.MILLISECONDS)
        }

        assertNotNull(hit, "Redis 채널을 통해 로컬 전달까지 도달해야 함")
        assertEquals(777L, hit!!.first, "userId 왕복")
        assertEquals("45", hit.second, "eventId 왕복")
        assertEquals(expected.json, hit.third, "wire 포맷(FE EventSource data) 동일")
    }

    companion object {
        private lateinit var redisProcess: Process
        private lateinit var connectionFactory: LettuceConnectionFactory
        private lateinit var template: StringRedisTemplate
        private lateinit var listenerContainer: RedisMessageListenerContainer
        private var redisPort = 0

        @JvmStatic
        @BeforeAll
        fun start() {
            redisPort = ServerSocket(0).use { it.localPort }
            redisProcess = ProcessBuilder(
                "redis-server", "--port", redisPort.toString(), "--save", "", "--appendonly", "no",
            ).redirectErrorStream(true).start()
            awaitRedisReady(redisPort)

            connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", redisPort))
            connectionFactory.afterPropertiesSet()
            connectionFactory.start()

            template = StringRedisTemplate(connectionFactory)
            template.afterPropertiesSet()

            listenerContainer = RedisMessageListenerContainer()
            listenerContainer.setConnectionFactory(connectionFactory)
            listenerContainer.afterPropertiesSet()
            listenerContainer.start()
        }

        private fun awaitRedisReady(port: Int) {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                runCatching {
                    Socket("127.0.0.1", port).use { s ->
                        s.getOutputStream().write("PING\r\n".toByteArray())
                        s.getOutputStream().flush()
                        if (s.getInputStream().read() == '+'.code) return // +PONG
                    }
                }
                Thread.sleep(50)
            }
            error("redis-server not ready on port $port")
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            if (::listenerContainer.isInitialized) listenerContainer.destroy()
            if (::connectionFactory.isInitialized) connectionFactory.destroy()
            if (::redisProcess.isInitialized) redisProcess.destroyForcibly()
        }
    }
}
