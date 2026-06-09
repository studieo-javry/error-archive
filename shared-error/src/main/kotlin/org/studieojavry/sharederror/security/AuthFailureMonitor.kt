package org.studieojavry.sharederror.security

/**
 * 인증 실패 빈도 모니터링 — 확장 포인트.
 *
 * 기본 구현은 [CaffeineAuthFailureMonitor] (앱 안 in-memory).
 * 향후 외부 WAF / CloudFlare / DDoS 보호 도입 시 다른 구현체로 교체 가능.
 * SharedErrorAutoConfiguration 의 @ConditionalOnMissingBean 으로 override.
 */
interface AuthFailureMonitor {
    /** 실패를 기록하고, 5분 윈도 안의 누적 실패 횟수를 반환. */
    fun recordFailure(ip: String): Int
}
