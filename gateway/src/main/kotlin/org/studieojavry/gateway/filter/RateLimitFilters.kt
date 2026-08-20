package org.studieojavry.gateway.filter

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.HandlerFilterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 게이트웨이 인메모리 per-IP 토큰버킷 rate limiter.
 *
 * 목적: 공개(무인증) publish 경로 — 특히 렌더 비용이 큰 PDF/MD export — 를 익명 남용/DoS 로부터 보호
 * (Spring Security로 못 막음: 이 경로들은 정의상 로그인 없이 열려 있어야 함.)
 *
 * ⚠️ 단일 인스턴스 전제(버킷이 프로세스 메모리). 게이트웨이를 수평 확장하면 인스턴스별로 별도 카운트가
 *    되므로 실질 한도가 N배로 늘어난다 → 그때는 Redis 등 분산 스토어(RequestRateLimiter)로 교체.
 */
@Component
class IpRateLimiter {

    private class Bucket(@Volatile var tokens: Double, @Volatile var lastRefillNanos: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val consumeCount = AtomicLong(0)

    /**
     * @param capacity     버스트 최대 토큰 (초기 가득)
     * @param refillPerSec 초당 보충 토큰 수 (정상 상태 허용률)
     * @return 1 토큰 소비 성공 시 true, 고갈이면 false
     */
    fun tryConsume(key: String, capacity: Double, refillPerSec: Double): Boolean {
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity, System.nanoTime()) }
        val allowed = synchronized(bucket) {
            val now = System.nanoTime()
            val elapsedSec = (now - bucket.lastRefillNanos) / 1_000_000_000.0
            bucket.tokens = (bucket.tokens + elapsedSec * refillPerSec).coerceAtMost(capacity)
            bucket.lastRefillNanos = now
            if (bucket.tokens >= 1.0) { bucket.tokens -= 1.0; true } else false
        }
        // 주기적 opportunistic 정리 — @Scheduled 없이 맵 무한 성장 방지.
        // 가득 찬(=한동안 미사용) 버킷만 제거 → 진행 중인 제한 상태는 보존.
        if (consumeCount.incrementAndGet() % SWEEP_INTERVAL == 0L && buckets.size > SWEEP_THRESHOLD) {
            buckets.entries.removeIf { (_, b) -> synchronized(b) { b.tokens >= capacity } }
        }
        return allowed
    }

    private companion object {
        const val SWEEP_INTERVAL = 2048L
        const val SWEEP_THRESHOLD = 10_000
    }
}

/** SCG Server MVC 라우트에 붙이는 per-IP rate-limit 필터 팩토리. */
object RateLimitFilters {

    /**
     * 실 클라이언트 IP 추출.
     *
     * 프로덕션은 Cloudflare(프록시) → NLB(L4, source IP SNAT) → gateway 경로라, TCP `remoteAddr` 는
     * NLB 사설 IP 로 뭉친다. Cloudflare 가 넣는 **CF-Connecting-IP** 가 진짜 클라 IP 다.
     * XFF(맨앞 항목)는 클라가 위조 가능하지만, CF-Connecting-IP 는 Cloudflare 가 항상 덮어써서 신뢰 가능 —
     * **단 origin(NLB/노드 443)이 Cloudflare IP 대역으로 잠긴 전제**(Security List)에서만 유효.
     * 헤더 없으면(local/dev 직접호출) remoteAddr(forward-headers-strategy 반영) 로 폴백.
     */
    private fun clientIp(request: ServerRequest): String {
        val cf = request.servletRequest().getHeader("CF-Connecting-IP")
        if (!cf.isNullOrBlank()) return cf.trim()
        return request.servletRequest().remoteAddr ?: "unknown"
    }

    /**
     * per-IP 토큰버킷 필터. 고갈 시 downstream 프록시 전에 429(problem+json)로 즉시 차단.
     * IP 는 [clientIp] 로 추출(CF-Connecting-IP 우선 → remoteAddr 폴백).
     */
    fun perIp(
        limiter: IpRateLimiter,
        name: String,
        capacity: Double,
        refillPerSec: Double,
    ): HandlerFilterFunction<ServerResponse, ServerResponse> =
        HandlerFilterFunction { request, next ->
            val ip = clientIp(request)
            if (limiter.tryConsume("$name|$ip", capacity, refillPerSec)) {
                next.handle(request)
            } else {
                ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "10")
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(
                        """{"type":"about:blank","title":"Too Many Requests","status":429,""" +
                            """"detail":"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."}""",
                    )
            }
        }
}
