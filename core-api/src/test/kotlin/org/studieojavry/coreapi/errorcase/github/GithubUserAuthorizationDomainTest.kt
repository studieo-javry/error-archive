package org.studieojavry.coreapi.errorcase.github

import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubUserAuthorization
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GithubUserAuthorization.needsRefresh] 경계 — 선제 갱신을 **언제** 트리거하는가. 이 판정이 틀리면
 * 멀쩡한 토큰을 매번 갱신하거나(낭비·회전 레이스), 만료 임박 토큰을 놓쳐 4xx→폐기→NOT_AUTHORIZED 가 난다.
 */
class GithubUserAuthorizationDomainTest {

    private val now = Instant.parse("2026-11-08T00:00:00Z")
    private val margin = Duration.ofMinutes(5)

    private fun auth(
        token: String = "utok",
        refreshToken: String? = "rtok",
        expiresAt: Instant? = now.plus(Duration.ofHours(8)),
        refreshTokenExpiresAt: Instant? = now.plus(Duration.ofDays(180)),
    ) = GithubUserAuthorization(
        eaUserId = 1, githubUserId = 2, githubLogin = "octo", token = token,
        createdAt = now, updatedAt = now,
        refreshToken = refreshToken, expiresAt = expiresAt, refreshTokenExpiresAt = refreshTokenExpiresAt,
    )

    @Test
    fun `refresh 토큰 없으면(App 만료 꺼짐·레거시) 갱신 안 함`() {
        assertFalse(auth(refreshToken = null, expiresAt = now.minusSeconds(1)).needsRefresh(now, margin))
    }

    @Test
    fun `만료 시각 모르면 갱신 안 함`() {
        assertFalse(auth(expiresAt = null).needsRefresh(now, margin))
    }

    @Test
    fun `여유 밖(만료 한참 남음)이면 갱신 안 함`() {
        assertFalse(auth(expiresAt = now.plus(Duration.ofHours(8))).needsRefresh(now, margin))
    }

    @Test
    fun `여유 안에 들면 갱신`() {
        assertTrue(auth(expiresAt = now.plus(Duration.ofMinutes(4))).needsRefresh(now, margin))
    }

    @Test
    fun `경계(정확히 만료-여유)에서 갱신`() {
        assertTrue(auth(expiresAt = now.plus(margin)).needsRefresh(now, margin))
    }

    @Test
    fun `이미 만료됐어도(오래 미접속) refresh 살아 있으면 갱신 가능`() {
        assertTrue(auth(expiresAt = now.minus(Duration.ofHours(1))).needsRefresh(now, margin))
    }

    @Test
    fun `refresh 토큰도 만료됐으면 갱신 시도 안 함(재인가 경로로)`() {
        assertFalse(
            auth(expiresAt = now.minusSeconds(1), refreshTokenExpiresAt = now.minusSeconds(1)).needsRefresh(now, margin),
        )
    }
}
