package org.studieojavry.gateway.filter

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import kotlin.math.max

/**
 * Redis 공유 버킷 per-IP 토큰버킷 rate limiter (기본 백엔드).
 *
 * [IpRateLimiter] 는 버킷이 프로세스 메모리라 게이트웨이를 N대로 띄우면 각자 카운트해 실질 한도가 N배가
 * 된다. 여기서는 버킷 상태(tokens, 마지막 보충 시각)를 **Redis 해시 한 곳**에 두어 모든 인스턴스가 같은
 * 버킷을 공유한다 → 인스턴스 수와 무관하게 설정한 한도가 그대로 적용된다.
 *
 * **원자성**: "읽기 → 보충 계산 → 소비 → 쓰기" 를 애플리케이션에서 나눠 하면 인스턴스 간 read-modify-write
 * 경합(lost update)으로 한도가 샌다. 그래서 전 과정을 **Lua 스크립트 한 방(EVAL)** 으로 Redis 안에서
 * 원자적으로 수행한다 — Redis 는 스크립트를 단일 스레드로 끝까지 실행하므로 동시 요청이 끼어들 수 없다.
 *
 * **시계**: 경과 시간은 각 인스턴스의 로컬 시계가 아니라 **Redis 서버의 `TIME`** 으로 잰다. 그래야
 * 인스턴스 간 시계 오차(clock skew)와 무관하게 보충량이 한 기준으로 계산된다.
 *
 * **키 만료**: 가득 찰 때까지 걸리는 시간(capacity/refillPerSec) 의 2배를 TTL 로 둔다. 그만큼 idle 이면
 * 어차피 버킷이 가득 차므로, 키가 만료돼 새 버킷(가득)으로 시작해도 동작이 동일하다 → 유휴 키 자동 청소.
 */
@Component
@ConditionalOnProperty(name = ["gateway.ratelimit.backend"], havingValue = "redis", matchIfMissing = true)
class RedisRateLimiter(
    private val redis: StringRedisTemplate,
) : RateLimiter {

    private val script: RedisScript<Long> = RedisScript.of(LUA, Long::class.javaObjectType)

    override fun tryConsume(key: String, capacity: Double, refillPerSec: Double): Boolean {
        val ttlMs = ttlMillis(capacity, refillPerSec)
        val allowed = redis.execute(
            script,
            listOf("$KEY_PREFIX$key"),
            capacity.toString(),
            refillPerSec.toString(),
            REQUESTED_TOKENS,
            ttlMs.toString(),
        )
        return allowed == 1L
    }

    /** 완충(가득)까지 걸리는 시간의 2배. refill 이 0 이면 회복 불가이므로 고정 TTL. */
    private fun ttlMillis(capacity: Double, refillPerSec: Double): Long {
        if (refillPerSec <= 0.0) return NO_REFILL_TTL_MS
        val fillMs = (capacity / refillPerSec) * 1000.0 * 2.0
        return max(MIN_TTL_MS, fillMs.toLong())
    }

    private companion object {
        const val KEY_PREFIX = "ratelimit:"
        const val REQUESTED_TOKENS = "1"
        const val MIN_TTL_MS = 1_000L
        const val NO_REFILL_TTL_MS = 3_600_000L // 1h — refill 0 버킷은 회복 안 되므로 넉넉히 유지

        /**
         * 토큰버킷 원자 소비. 버킷 상태는 해시 {tokens, ts(ms)} 로 저장.
         *  KEYS[1] = 버킷 키
         *  ARGV[1] = capacity, ARGV[2] = refillPerSec, ARGV[3] = 요청 토큰, ARGV[4] = ttl(ms)
         *  반환     = 1(허용) / 0(고갈)
         */
        val LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            local t = redis.call('TIME')
            local now = (tonumber(t[1]) * 1000) + (tonumber(t[2]) / 1000)

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end

            local elapsed = (now - ts) / 1000.0
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + (elapsed * refill))

            local allowed = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            end

            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            redis.call('PEXPIRE', key, ttl)
            return allowed
        """.trimIndent()
    }
}
