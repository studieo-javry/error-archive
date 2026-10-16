package org.studieojavry.notiapi.notification.infrastructure.sse

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.studieojavry.notiapi.notification.application.sse.RealtimePublisherPort
import org.studieojavry.notiapi.notification.domain.Notification
import org.studieojavry.notiapi.notification.domain.NotificationType
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import kotlin.concurrent.thread

/**
 * 후속 검증 — noti `realtime.backend=redis` **전체 Spring 경로**가 실제로 기동·동작함을 확인한다.
 *
 * 기존 [RedisRealtimeFanoutTest] 는 pub/sub 배선을 손으로 구성해 왕복만 봤고(= Spring auto-config 미경유),
 * 이 테스트는 **@SpringBootTest 로 컨텍스트를 띄워**:
 *  1. backend=redis 일 때 [RealtimePublisherPort] 빈이 [RedisRealtimePublisher] 로 해소되고,
 *  2. [RedisRealtimeConfig] 의 [RedisMessageListenerContainer] 빈이 실제로 기동되며,
 *  3. 그 publisher 로 발행한 이벤트가 **실 Redis 채널 → Spring 이 등록한 구독자 → broker 로컬 전달**
 *     까지 온전히 이어지는지
 * 를 검증한다. (= "발생 노드 ≠ 접속 노드" 전달의 Spring-와이어링 버전.)
 *
 * broker 를 spy 로 바꿔 `deliverToLocal(userId, eventId, data)` 호출을 확인한다(실제 emitter 불요).
 * 구독 활성화는 비동기 → 수신될 때까지 백그라운드로 재발행(pub/sub 은 구독 전 발행 유실).
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("redisServerOnPath") // redis-server 바이너리 없으면(CI) skip
class RedisRealtimeSpringWiringTest {

    @MockitoSpyBean
    private lateinit var broker: NotificationSseBroker

    @Autowired
    private lateinit var publisher: RealtimePublisherPort

    @Autowired
    private lateinit var listenerContainer: RedisMessageListenerContainer

    @Test
    @DisplayName("backend=redis: Spring 이 publisher/구독 컨테이너를 기동 → 발행이 실 Redis 거쳐 broker 로컬 전달까지 도달")
    fun redis_backend_full_spring_path_delivers() {
        // (1) 백엔드 선택이 Spring 수준에서 redis 로 해소됐는가.
        assertInstanceOf(RedisRealtimePublisher::class.java, publisher, "backend=redis → RedisRealtimePublisher 주입")
        assert(listenerContainer.isRunning) { "RedisMessageListenerContainer 가 기동 중이어야 함" }

        // broker.deliverToLocal 수신을 큐로 캡처(spy stubbing 은 doAnswer/when 형태라 실제 호출 안 함).
        val received = java.util.concurrent.LinkedBlockingQueue<Triple<Long, String, String>>()
        doAnswer { inv ->
            received.add(Triple(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))
            null
        }.`when`(broker).deliverToLocal(anyLong(), anyString(), anyString())

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

        // 구독 활성화 레이스 회피: 전달이 확인될 때까지 백그라운드로 재발행(pub/sub 은 구독 전 발행 유실).
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val pump = thread(isDaemon = true) {
            while (!stop.get()) {
                runCatching { publisher.publishToUser(777L, notification) }
                Thread.sleep(150)
            }
        }
        try {
            // (2)(3) 실 Redis 채널 → Spring 등록 구독자 → broker.deliverToLocal 도달(최대 5s).
            var hit: Triple<Long, String, String>? = null
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && hit == null) {
                val t = received.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (t.first == 777L && t.second == "45") hit = t
            }
            assertNotNull(hit, "실 Redis 채널 → Spring 구독자 → broker.deliverToLocal 까지 도달해야 함")
            assertEquals(expected.json, hit!!.third, "wire 포맷(FE EventSource data) 동일")
        } finally {
            stop.set(true)
            pump.join(1_000)
        }
    }

    companion object {
        /** redis-server 바이너리가 PATH 에 있어 기동 가능한가 — 없으면 이 테스트 클래스를 skip. */
        @JvmStatic
        fun redisServerOnPath(): Boolean = try {
            val p = ProcessBuilder("redis-server", "--version").redirectErrorStream(true).start()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); p.destroy(); true
        } catch (e: Exception) {
            false
        }

        private lateinit var redisProcess: Process
        private var redisPort = 0

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            redisPort = ServerSocket(0).use { it.localPort }
            redisProcess = ProcessBuilder(
                "redis-server", "--port", redisPort.toString(), "--save", "", "--appendonly", "no",
            ).redirectErrorStream(true).start()
            awaitRedisReady(redisPort)
            registry.add("noti.realtime.backend") { "redis" }   // test 기본(local) 을 redis 로 override
            registry.add("spring.data.redis.host") { "127.0.0.1" }
            registry.add("spring.data.redis.port") { redisPort }
        }

        private fun awaitRedisReady(port: Int) {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                runCatching {
                    Socket("127.0.0.1", port).use { s ->
                        s.getOutputStream().write("PING\r\n".toByteArray())
                        s.getOutputStream().flush()
                        if (s.getInputStream().read() == '+'.code) return
                    }
                }
                Thread.sleep(50)
            }
            error("redis-server not ready on port $port")
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            if (::redisProcess.isInitialized) redisProcess.destroyForcibly()
        }
    }
}
