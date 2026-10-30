package org.studieojavry.coreapi.errorcase.case.infrastructure.ratelimit

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.DraftRateLimiterPort
import org.studieojavry.coreapi.errorcase.case.config.LlmRateLimitProperties
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Redis 공유 카운터 기반 리미터(멀티 파드 일관).
 *
 * 고정창(fixed window) `INCR`+`EXPIRE`:
 *  - 버스트  `llm:rl:min:{userId}:{epochMinute}`  (TTL 60s)
 *  - 일 쿼터 `llm:rl:day:{userId}:{yyyy-MM-dd}`   (TTL 26h)
 *  - 전역예산 `llm:rl:budget:{yyyy-MM-dd}`         (TTL 26h)
 *
 * 토큰버킷(bucket4j)처럼 매끄럽진 않지만 **의존성 없이** 비용 상한을 건다. 창 경계에서 최대 2배 버스트가
 * 가능한 고정창의 약점은, 비용 방어라는 목적상 수용 가능(정밀 제어가 필요하면 추후 Lua/슬라이딩창).
 * 시각은 UTC 기준(파드 TZ 무관 일관).
 */
@Component
@ConditionalOnProperty(prefix = "llm.rate-limit", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RedisDraftRateLimiter(
    private val redis: StringRedisTemplate,
    private val props: LlmRateLimitProperties,
) : DraftRateLimiterPort {

    override fun checkUser(userId: Long): DraftRateLimiterPort.Decision {
        val now = ZonedDateTime.now(ZoneOffset.UTC)

        val minKey = "llm:rl:min:$userId:${now.toEpochSecond() / 60}"
        if (incrWithTtl(minKey, Duration.ofSeconds(60)) > props.perMinute) {
            return DraftRateLimiterPort.Decision(false, secondsToNextMinute(now), "per-minute")
        }

        val dayKey = "llm:rl:day:$userId:${now.toLocalDate()}"
        if (incrWithTtl(dayKey, Duration.ofHours(26)) > props.perDay) {
            return DraftRateLimiterPort.Decision(false, secondsToNextUtcMidnight(now), "per-day")
        }

        return DraftRateLimiterPort.Decision(true)
    }

    override fun tryConsumeGlobalBudget(): Boolean {
        if (props.globalPerDay <= 0) return true // 미설정 = 무제한
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val key = "llm:rl:budget:${now.toLocalDate()}"
        return incrWithTtl(key, Duration.ofHours(26)) <= props.globalPerDay
    }

    /** INCR 후 TTL 보장(첫 증가 시, 혹은 크래시 등으로 TTL 유실된 키 복구) → TTL 없는 카운터가 영구 차단하는 것 방지. */
    private fun incrWithTtl(key: String, ttl: Duration): Long {
        val v = redis.opsForValue().increment(key) ?: 1L
        if (v == 1L || (redis.getExpire(key) ?: -1L) < 0L) redis.expire(key, ttl)
        return v
    }

    private fun secondsToNextMinute(now: ZonedDateTime): Long =
        60L - now.second

    private fun secondsToNextUtcMidnight(now: ZonedDateTime): Long =
        Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC)).seconds
}
