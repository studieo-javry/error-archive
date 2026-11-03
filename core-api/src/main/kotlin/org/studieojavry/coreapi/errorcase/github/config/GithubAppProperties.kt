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
    val rateLimit: RateLimit = RateLimit(),
    val reconcile: Reconcile = Reconcile(),
) {
    /** 웹훅 inbox 처리기 파라미터. backoff = min(2^attempts * base, max). */
    data class Inbox(
        val pollDelayMs: Long = 2000,
        val batchSize: Int = 20,
        val maxAttempts: Int = 8,
        val baseBackoffMs: Long = 5000,
        val maxBackoffMs: Long = 3_600_000,
    )

    /**
     * GitHub 호출 한도 나눠 쓰기. 백그라운드 작업(웹훅 처리·재동기화)은 남은 양이 한도의 [backgroundReserveRatio] 이하가 되면
     * 리셋까지 멈추고 그 몫을 사용자 요청(연결·검색·가져오기)에 남긴다.
     */
    data class RateLimit(
        val backgroundReserveRatio: Double = 0.2,
    )

    /**
     * 놓친 웹훅 재동기화 — GitHub 는 전달에 실패한 웹훅을 다시 보내지 않으므로, 붙은 Issue/PR 을 주기적으로 다시 조회해 맞춘다.
     * [pollDelayMs] 마다 최대 [batchSize] 개, 같은 대상은 [minInterval] 에 한 번. 대상 = 열림·CI 진행 중이거나 [recentWindow] 안에 바뀐 것.
     */
    data class Reconcile(
        val enabled: Boolean = true,
        val initialDelayMs: Long = 120_000,
        val pollDelayMs: Long = 900_000,
        val batchSize: Int = 30,
        val minInterval: Duration = Duration.ofHours(1),
        val recentWindow: Duration = Duration.ofDays(7),
    )

    @AssertTrue(
        message = "core.github.enabled=true 이면 app-id/app-slug/client-id/client-secret/private-key-pem/webhook-secret/state-secret 필수 — CORE_GITHUB_* 환경변수 확인",
    )
    fun isConfiguredWhenEnabled(): Boolean =
        !enabled || listOf(appId, appSlug, clientId, clientSecret, privateKeyPem, webhookSecret, stateSecret)
            .all { !it.isNullOrBlank() && !it.contains("\${") }
}
