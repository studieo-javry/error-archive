package org.studieojavry.coreapi.errorcase.case.infrastructure.ratelimit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.studieojavry.coreapi.errorcase.case.config.LlmRateLimitProperties

/**
 * 임계값 판정 로직 검증 — Redis 는 in-memory 카운터로 모킹(INCR 를 키별 누적으로 흉내).
 * 실제 Redis 왕복(네트워크·TTL)은 `IamCacheVerificationTest` 식 실-redis 통합으로 별도 검증(후속).
 */
class RedisDraftRateLimiterTest {

    @Suppress("UNCHECKED_CAST")
    private fun limiter(props: LlmRateLimitProperties): RedisDraftRateLimiter {
        val store = HashMap<String, Long>()
        val template = Mockito.mock(StringRedisTemplate::class.java)
        val valueOps = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        Mockito.`when`(template.opsForValue()).thenReturn(valueOps)
        Mockito.`when`(valueOps.increment(anyString())).thenAnswer { inv ->
            val k = inv.getArgument<String>(0)
            val v = (store[k] ?: 0L) + 1L
            store[k] = v
            v
        }
        return RedisDraftRateLimiter(template, props)
    }

    @Test
    fun `분당 상한을 넘으면 per-minute 로 거절한다`() {
        val rl = limiter(LlmRateLimitProperties(perMinute = 2, perDay = 100))
        assertTrue(rl.checkUser(1).allowed)   // 1
        assertTrue(rl.checkUser(1).allowed)   // 2
        val third = rl.checkUser(1)           // 3 > 2
        assertFalse(third.allowed)
        assertTrue(third.retryAfterSeconds in 0..60)
        assert(third.reason == "per-minute")
    }

    @Test
    fun `일일 쿼터를 넘으면 per-day 로 거절한다`() {
        val rl = limiter(LlmRateLimitProperties(perMinute = 100, perDay = 2))
        assertTrue(rl.checkUser(1).allowed)   // day 1
        assertTrue(rl.checkUser(1).allowed)   // day 2
        val third = rl.checkUser(1)           // day 3 > 2
        assertFalse(third.allowed)
        assert(third.reason == "per-day")
    }

    @Test
    fun `사용자별로 카운터가 분리된다`() {
        val rl = limiter(LlmRateLimitProperties(perMinute = 1, perDay = 100))
        assertTrue(rl.checkUser(1).allowed)
        assertFalse(rl.checkUser(1).allowed)  // user1 두 번째 → 분당 1 초과
        assertTrue(rl.checkUser(2).allowed)   // user2 는 영향 없음
    }

    @Test
    fun `전역 예산 상한까지만 소비를 허용한다`() {
        val rl = limiter(LlmRateLimitProperties(globalPerDay = 2))
        assertTrue(rl.tryConsumeGlobalBudget())   // 1
        assertTrue(rl.tryConsumeGlobalBudget())   // 2
        assertFalse(rl.tryConsumeGlobalBudget())  // 3 > 2
    }

    @Test
    fun `전역 예산 미설정이면 항상 허용하고 카운트하지 않는다`() {
        val rl = limiter(LlmRateLimitProperties(globalPerDay = 0))
        repeat(5) { assertTrue(rl.tryConsumeGlobalBudget()) }
    }
}
