package org.studieojavry.coreapi.errorcase.sentry.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Sentry 연동 설정. `enabled=false`(기본) 면 연동 빈 전부 미등록 → 시크릿 없이도 부팅.
 *
 * ★ fail-fast: [clientSecret] 에 의미 있는 기본값을 두지 않는다(기본값이 미해소 `${'$'}{ENV}` 를 가려
 *   조용히 부팅되는 함정). `enabled=true` 인데 비었거나 미해소 placeholder 면 부팅 중단.
 */
@Validated
@ConfigurationProperties(prefix = "core.sentry")
data class SentryProperties(
    val enabled: Boolean = false,
    /** 웹훅 서명 검증용 공유 시크릿(Sentry Integration client secret). 단일 키로 모든 프로젝트 웹훅 검증. */
    val clientSecret: String? = null,
    val inbox: Inbox = Inbox(),
) {
    /** 웹훅 inbox 처리기 파라미터. backoff = min(2^attempts * base, max). */
    data class Inbox(
        val pollDelayMs: Long = 2000,
        val batchSize: Int = 20,
        val maxAttempts: Int = 8,
        val baseBackoffMs: Long = 5000,
        val maxBackoffMs: Long = 3_600_000,
    )

    @AssertTrue(
        message = "core.sentry.enabled=true 이면 client-secret 필수 — CORE_SENTRY_CLIENT_SECRET 환경변수 확인",
    )
    fun isConfiguredWhenEnabled(): Boolean {
        if (!enabled) return true
        val secret = clientSecret
        return !secret.isNullOrBlank() && !secret.contains("\${")
    }
}
