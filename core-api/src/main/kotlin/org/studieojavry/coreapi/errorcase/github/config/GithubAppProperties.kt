package org.studieojavry.coreapi.errorcase.github.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * GitHub App 연동 설정. `enabled=false`(기본) 면 연동 빈 전부 미등록 → 시크릿 없이도 부팅.
 *
 * ★ fail-fast: 시크릿 필드에 *의미 있는 기본값을 두지 않는다*(기본값이 미해소 `${ENV}` 를 가려 조용히 부팅되는 함정).
 *   yml 은 `${CORE_GITHUB_*:}` (빈 문자열 폴백) → `enabled=true` 인데 비었거나 미해소 placeholder 면
 *   [isConfiguredWhenEnabled] 가 false → 부팅 중단.
 */
@Validated
@ConfigurationProperties(prefix = "core.github")
data class GithubAppProperties(
    val enabled: Boolean = false,
    /** JWT `iss` 로 쓰는 App ID(숫자). */
    val appId: String? = null,
    /** 설치 페이지 URL(`/apps/{slug}/installations/new`) 조립용. */
    val appSlug: String? = null,
    /** 설치 콜백의 `code` → 사용자 토큰 교환용(설치 소유 검증에만 쓰고 버림). */
    val clientId: String? = null,
    val clientSecret: String? = null,
    /** App private key PEM — GitHub 가 내려주는 PKCS#1(`BEGIN RSA PRIVATE KEY`) 그대로 또는 PKCS#8. */
    val privateKeyPem: String? = null,
    /** 웹훅 `X-Hub-Signature-256` HMAC 키. */
    val webhookSecret: String? = null,
    /** 설치 state 서명 키 — 웹훅 시크릿과 분리(용도별 키). */
    val stateSecret: String? = null,
    val apiBaseUrl: String = "https://api.github.com",
    val webBaseUrl: String = "https://github.com",
    val stateTtl: Duration = Duration.ofMinutes(10),
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
        message = "core.github.enabled=true 이면 app-id/app-slug/client-id/client-secret/private-key-pem/webhook-secret/state-secret 필수 — CORE_GITHUB_* 환경변수 확인",
    )
    fun isConfiguredWhenEnabled(): Boolean =
        !enabled || listOf(appId, appSlug, clientId, clientSecret, privateKeyPem, webhookSecret, stateSecret)
            .all { !it.isNullOrBlank() && !it.contains("\${") }
}
