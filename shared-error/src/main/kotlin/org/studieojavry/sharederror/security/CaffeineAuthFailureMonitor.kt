package org.studieojavry.sharederror.security

import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 기본 구현 — 5 분 윈도, IP 별 카운터. brute-force 탐지용.
 *
 * 향후 외부 WAF/CloudFlare 로 위임 시 별도 AuthFailureMonitor 구현체 제공 +
 * @ConditionalOnMissingBean 으로 substitute.
 */
class CaffeineAuthFailureMonitor : AuthFailureMonitor {

    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(10_000)
        .build<String, AtomicInteger>()

    override fun recordFailure(ip: String): Int =
        cache.get(ip) { AtomicInteger() }.incrementAndGet()
}
