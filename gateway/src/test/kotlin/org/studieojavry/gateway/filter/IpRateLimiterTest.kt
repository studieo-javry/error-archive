package org.studieojavry.gateway.filter

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * IpRateLimiter 토큰버킷 로직 단위 테스트.
 *
 * refill 이 실제로 채워지는지는 System.nanoTime 에 의존하므로, refill 을 매우 크게 잡고 짧은
 * 실시간 sleep(넉넉한 여유)으로 검증 — "필요한 양 << 실제 경과"라 조기 반환에도 안전(flaky X).
 * capacity·격리·고갈처럼 시간과 무관한 성질은 refill=0 으로 결정론적 검증.
 */
class IpRateLimiterTest {

    @Test
    fun `버스트 capacity 만큼 통과 후 고갈되면 차단`() {
        val limiter = IpRateLimiter()
        // refill 0 → 시간이 지나도 안 채워짐(결정론적).
        repeat(5) { i ->
            assertTrue(limiter.tryConsume("ip-a", capacity = 5.0, refillPerSec = 0.0), "burst #$i 통과해야")
        }
        assertFalse(limiter.tryConsume("ip-a", capacity = 5.0, refillPerSec = 0.0), "6번째는 고갈로 차단")
        assertFalse(limiter.tryConsume("ip-a", capacity = 5.0, refillPerSec = 0.0), "이후도 계속 차단")
    }

    @Test
    fun `IP(key) 별로 버킷이 독립적`() {
        val limiter = IpRateLimiter()
        // ip-a 를 다 소진해도 ip-b 는 영향 없음.
        repeat(3) { limiter.tryConsume("ip-a", capacity = 3.0, refillPerSec = 0.0) }
        assertFalse(limiter.tryConsume("ip-a", capacity = 3.0, refillPerSec = 0.0), "a 는 고갈")
        assertTrue(limiter.tryConsume("ip-b", capacity = 3.0, refillPerSec = 0.0), "b 는 가득 → 통과")
    }

    @Test
    fun `시간이 지나면 refill 로 토큰이 회복`() {
        val limiter = IpRateLimiter()
        // capacity 1, refill 1000/s(=1토큰/ms). 소진 후 즉시엔 차단, 20ms 뒤엔 회복.
        assertTrue(limiter.tryConsume("ip-c", capacity = 1.0, refillPerSec = 1000.0), "첫 요청 통과")
        assertFalse(limiter.tryConsume("ip-c", capacity = 1.0, refillPerSec = 1000.0), "즉시 재요청은 고갈")
        Thread.sleep(20) // 필요치(1ms) << 경과(20ms) → 최소 1토큰 회복 보장
        assertTrue(limiter.tryConsume("ip-c", capacity = 1.0, refillPerSec = 1000.0), "회복 후 통과")
    }

    @Test
    fun `refill 은 capacity 를 넘지 않음`() {
        val limiter = IpRateLimiter()
        // capacity 2. 오래 idle 해도 최대 2개까지만 → 버스트 3개면 2 통과 + 1 차단.
        assertTrue(limiter.tryConsume("ip-d", capacity = 2.0, refillPerSec = 1000.0))
        assertTrue(limiter.tryConsume("ip-d", capacity = 2.0, refillPerSec = 1000.0))
        limiter.tryConsume("ip-d", capacity = 2.0, refillPerSec = 1000.0) // 소진
        Thread.sleep(50) // 충분히 idle → 이론상 수십 토큰이지만 cap=2 로 상한
        assertTrue(limiter.tryConsume("ip-d", capacity = 2.0, refillPerSec = 1000.0), "회복분 1")
        assertTrue(limiter.tryConsume("ip-d", capacity = 2.0, refillPerSec = 1000.0), "회복분 2")
        assertFalse(limiter.tryConsume("ip-d", capacity = 2.0, refillPerSec = 1000.0), "capacity 상한 → 3번째 차단")
    }
}
