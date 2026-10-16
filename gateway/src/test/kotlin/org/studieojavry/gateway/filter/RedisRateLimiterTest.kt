package org.studieojavry.gateway.filter

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * RedisRateLimiter(Lua 토큰버킷) 동작 검증 — **실제 redis-server** 를 ephemeral 포트에 띄워 확인.
 *
 * [IpRateLimiterTest] 와 같은 성질(버스트·고갈·격리·refill 상한)을 Redis 백엔드에서도 보장하는지 본다.
 * Spring 컨텍스트 없이 LettuceConnectionFactory + StringRedisTemplate 를 직접 구성해 limiter 를
 * new 하므로(=기존 단위테스트 스타일), 애플리케이션 설정/환경변수가 필요 없다.
 *
 * 시간 의존 테스트(refill)는 refill 주기를 **Redis 왕복(~ms)보다 훨씬 크게**(10/s = 100ms/토큰) 잡는다.
 * 인메모리와 달리 각 tryConsume 이 네트워크 홉이라, refill 이 너무 빠르면(예: 1000/s) 두 호출 사이 ~1ms
 * 왕복만으로 토큰이 차버려 "즉시 고갈" 가정이 깨진다. 느린 refill + 넉넉한 sleep 으로 결정론을 확보한다.
 * 각 테스트는 유니크 key prefix 를 써 서로 간섭하지 않는다.
 */
class RedisRateLimiterTest {

    private fun key(name: String) = "$name-${UUID.randomUUID()}"

    @Test
    fun `버스트 capacity 만큼 통과 후 고갈되면 차단`() {
        val k = key("burst")
        repeat(5) { i ->
            assertTrue(limiter.tryConsume(k, capacity = 5.0, refillPerSec = 0.0), "burst #$i 통과해야")
        }
        assertFalse(limiter.tryConsume(k, capacity = 5.0, refillPerSec = 0.0), "6번째는 고갈로 차단")
        assertFalse(limiter.tryConsume(k, capacity = 5.0, refillPerSec = 0.0), "이후도 계속 차단")
    }

    @Test
    fun `IP(key) 별로 버킷이 독립적(공유 스토어에서도)`() {
        val a = key("iso-a")
        val b = key("iso-b")
        repeat(3) { limiter.tryConsume(a, capacity = 3.0, refillPerSec = 0.0) }
        assertFalse(limiter.tryConsume(a, capacity = 3.0, refillPerSec = 0.0), "a 는 고갈")
        assertTrue(limiter.tryConsume(b, capacity = 3.0, refillPerSec = 0.0), "b 는 가득 → 통과")
    }

    @Test
    fun `시간이 지나면 refill 로 토큰이 회복`() {
        val k = key("refill")
        // refill 10/s(=100ms/토큰). 왕복 ~ms 로는 토큰이 안 차 "즉시 고갈"이 유지되고, 300ms 뒤엔 회복.
        assertTrue(limiter.tryConsume(k, capacity = 1.0, refillPerSec = 10.0), "첫 요청 통과")
        assertFalse(limiter.tryConsume(k, capacity = 1.0, refillPerSec = 10.0), "즉시 재요청은 고갈")
        Thread.sleep(300) // 필요치(100ms) << 경과(300ms) → 최소 1토큰 회복 보장
        assertTrue(limiter.tryConsume(k, capacity = 1.0, refillPerSec = 10.0), "회복 후 통과")
    }

    @Test
    fun `refill 은 capacity 를 넘지 않음`() {
        val k = key("cap")
        // capacity 2, refill 10/s. 오래 idle 해도 최대 2개까지만 → 2 통과 + 1 차단.
        assertTrue(limiter.tryConsume(k, capacity = 2.0, refillPerSec = 10.0))
        assertTrue(limiter.tryConsume(k, capacity = 2.0, refillPerSec = 10.0))
        limiter.tryConsume(k, capacity = 2.0, refillPerSec = 10.0) // 소진
        Thread.sleep(500) // 이론상 5토큰이지만 cap=2 로 상한
        assertTrue(limiter.tryConsume(k, capacity = 2.0, refillPerSec = 10.0), "회복분 1")
        assertTrue(limiter.tryConsume(k, capacity = 2.0, refillPerSec = 10.0), "회복분 2")
        assertFalse(limiter.tryConsume(k, capacity = 2.0, refillPerSec = 10.0), "capacity 상한 → 3번째 차단")
    }

    @Test
    fun `두 인스턴스가 한 Redis 버킷을 공유 — 합산 한도 유지(수평 확장)`() {
        val k = key("shared")
        // node B: 별도 연결 팩토리/템플릿 = 또 하나의 게이트웨이 인스턴스를 모사(같은 Redis 를 공유).
        val cfB = LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", redisPort))
            .apply { afterPropertiesSet(); start() }
        val nodeB = RedisRateLimiter(StringRedisTemplate(cfB).apply { afterPropertiesSet() })
        val nodeA = limiter
        try {
            val cap = 6.0
            var allowed = 0
            // A·B 번갈아 소비 — 각자 6 이 아니라 **두 노드 합쳐 6** 만 통과해야(한도 공유).
            for (i in 0 until 12) {
                val node = if (i % 2 == 0) nodeA else nodeB
                if (node.tryConsume(k, capacity = cap, refillPerSec = 0.0)) allowed++
            }
            assertEquals(6, allowed, "두 인스턴스 합산 통과가 단일 capacity(6)로 제한 — 인스턴스 수만큼 한도가 늘지 않음")
            assertFalse(nodeA.tryConsume(k, cap, 0.0), "고갈 후 node A 도 차단")
            assertFalse(nodeB.tryConsume(k, cap, 0.0), "고갈 후 node B 도 차단")
        } finally {
            cfB.destroy()
        }
    }

    companion object {
        private lateinit var redisProcess: Process
        private lateinit var connectionFactory: LettuceConnectionFactory
        private lateinit var limiter: RedisRateLimiter
        private var redisPort: Int = 0

        @JvmStatic
        @BeforeAll
        fun startRedisAndLimiter() {
            redisPort = ServerSocket(0).use { it.localPort }
            redisProcess = ProcessBuilder(
                "redis-server", "--port", redisPort.toString(), "--save", "", "--appendonly", "no",
            ).redirectErrorStream(true).start()
            awaitRedisReady(redisPort)

            connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", redisPort))
            connectionFactory.afterPropertiesSet()
            connectionFactory.start()
            val template = StringRedisTemplate(connectionFactory).apply { afterPropertiesSet() }
            limiter = RedisRateLimiter(template)
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
            if (::connectionFactory.isInitialized) connectionFactory.destroy()
            if (::redisProcess.isInitialized) redisProcess.destroyForcibly()
        }
    }
}
