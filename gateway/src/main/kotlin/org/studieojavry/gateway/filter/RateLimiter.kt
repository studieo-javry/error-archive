package org.studieojavry.gateway.filter

/**
 * per-key(=per-IP) 토큰버킷 rate limiter 추상화.
 *
 * 구현은 백엔드로 교체 가능하다 — 라우트([org.studieojavry.gateway.config.RouteConfig])와
 * 필터([RateLimitFilters.perIp])는 이 인터페이스에만 의존하므로 백엔드가 바뀌어도 그대로다.
 *
 *  - [IpRateLimiter]    : 인메모리(프로세스 로컬). 단일 인스턴스 전용. `gateway.ratelimit.backend=memory`.
 *  - [RedisRateLimiter] : Redis 공유 버킷(기본). 수평 확장 시 전 인스턴스가 **하나의** 버킷을 공유해
 *                         실질 한도를 유지한다. `gateway.ratelimit.backend=redis`(기본).
 */
interface RateLimiter {

    /**
     * @param key          버킷 식별자 (예: `"publish-export|1.2.3.4"`)
     * @param capacity     버스트 최대 토큰 (초기 가득)
     * @param refillPerSec 초당 보충 토큰 수 (정상 상태 허용률)
     * @return 1 토큰 소비 성공 시 true, 고갈이면 false
     */
    fun tryConsume(key: String, capacity: Double, refillPerSec: Double): Boolean
}
