package org.studieojavry.coreapi.errorcase.github.domain.model

import java.time.Duration
import java.time.Instant

/**
 * 한 EA 사용자가 GitHub OAuth 로 내준 사용자 토큰(user-to-server). 저장 목적:
 *  - 시나리오 ②(재연결 시 GitHub 재이동 없이 설치/레포를 다시 조회) 를 위해 유효한 사용자 토큰을 재사용.
 *  - `github_app_authorization` revoked 웹훅이 오면 [githubUserId] 로 찾아 폐기.
 *
 * [token]·[refreshToken] 은 평문(도메인 경계 안). 저장 어댑터가 AES-GCM 으로 암호화해 넣고 꺼낼 때 복호화한다 — DB 에는 평문이 없다.
 * EA 사용자당 1건(재인가 시 upsert). 사용자가 GitHub 계정을 바꾸면 [githubUserId] 가 바뀌며 덮어쓴다.
 *
 * GitHub App 이 "Expire user authorization tokens" 를 켜면 [token] 은 [expiresAt](~8h)에 만료되고,
 * [refreshToken](~6개월, [refreshTokenExpiresAt])으로 재인가 없이 갱신할 수 있다. App 이 만료를 끈 경우엔 토큰이
 * 무기한이라 갱신 관련 필드가 모두 null — 그땐 선제 갱신을 건너뛴다(만료 자체가 없음).
 */
data class GithubUserAuthorization(
    val eaUserId: Long,
    val githubUserId: Long,
    val githubLogin: String,
    val token: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 사용자 토큰 갱신용 refresh 토큰(App 만료 설정이 켜졌을 때만 발급). null 이면 갱신 불가 → 만료 시 재인가. */
    val refreshToken: String? = null,
    /** [token] 만료 시각. null = 만료 없음(App 설정 꺼짐) 또는 미상(V18 레거시 행). */
    val expiresAt: Instant? = null,
    /** [refreshToken] 만료 시각. 지난 뒤엔 갱신도 불가 → 재인가 필요. */
    val refreshTokenExpiresAt: Instant? = null,
) {
    /**
     * [token] 이 [margin] 안에 만료되거나 이미 만료됐고, 갱신할 refresh 토큰이 아직 살아 있는가.
     * true 면 호출 전에 선제 갱신해야 한다. 갱신 정보가 없는 행(App 만료 꺼짐/레거시)은 항상 false.
     */
    fun needsRefresh(now: Instant, margin: Duration): Boolean {
        if (refreshToken == null || expiresAt == null) return false
        if (refreshTokenExpiresAt != null && !now.isBefore(refreshTokenExpiresAt)) return false
        return !now.isBefore(expiresAt.minus(margin))
    }
}
