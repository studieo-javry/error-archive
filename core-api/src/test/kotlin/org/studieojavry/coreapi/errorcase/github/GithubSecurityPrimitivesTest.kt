package org.studieojavry.coreapi.errorcase.github

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubInstallStateCodec
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubInstallStateInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubWebhookSignatureVerifier
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.api.GithubAppJwtSigner
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 서명·state·App JWT·순서 역전 방어 — GitHub 연동에서 보안/정합을 책임지는 작은 부품들. */
class GithubSecurityPrimitivesTest {

    // ── 웹훅 서명 ───────────────────────────────────────────────
    @Test
    fun `웹훅 서명 - 원본 바이트 기준으로 맞으면 통과, 한 글자라도 다르면 거부`() {
        val body = """{"action":"added"}""".toByteArray()
        val header = "sha256=" + GithubWebhookSignatureVerifier.sign("s3cret", body)

        assertTrue(GithubWebhookSignatureVerifier.isValid("s3cret", body, header))
        assertFalse(GithubWebhookSignatureVerifier.isValid("other", body, header))
        assertFalse(GithubWebhookSignatureVerifier.isValid("s3cret", """{"action":"removed"}""".toByteArray(), header))
        assertFalse(GithubWebhookSignatureVerifier.isValid("s3cret", body, null))
        assertFalse(GithubWebhookSignatureVerifier.isValid("s3cret", body, header.removePrefix("sha256=")))
    }

    // ── 설치 state ─────────────────────────────────────────────
    private val props = GithubAppProperties(stateSecret = "state-secret")
    private fun clockAt(epoch: Long) = Clock.fixed(Instant.ofEpochSecond(epoch), ZoneOffset.UTC)

    @Test
    fun `state - 발급한 사용자·워크스페이스가 그대로 복원된다`() {
        val codec = GithubInstallStateCodec(props, clockAt(1_000))
        assertEquals(GithubInstallStateCodec.State(7, 42), codec.verify(codec.issue(7, 42)))
        assertNull(codec.verify(codec.issue(7, null)).workspaceId)
    }

    @Test
    fun `state - 위조·다른 키·만료는 거부`() {
        val codec = GithubInstallStateCodec(props, clockAt(1_000))
        val state = codec.issue(7, 42)

        // 본문을 바꿔치기(userId 7 → 8) — 서명 불일치
        val forgedBody = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(String(Base64.getUrlDecoder().decode(state.substringBefore('.'))).replace("|7|", "|8|").toByteArray())
        assertThrows<GithubInstallStateInvalidException> { codec.verify(forgedBody + "." + state.substringAfter('.')) }
        // 다른 서버 키로 서명된 state
        assertThrows<GithubInstallStateInvalidException> {
            GithubInstallStateCodec(GithubAppProperties(stateSecret = "other"), clockAt(1_000)).verify(state)
        }
        // TTL(10분) 경과
        assertThrows<GithubInstallStateInvalidException> {
            GithubInstallStateCodec(props, clockAt(1_000 + 601)).verify(state)
        }
        assertThrows<GithubInstallStateInvalidException> { codec.verify("garbage") }
    }

    // ── App JWT ───────────────────────────────────────────────
    @Test
    fun `App JWT - GitHub 기본 형식인 PKCS1 키와 PKCS8 키 모두로 RS256 서명하고 공개키로 검증된다`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs8 = kp.private.encoded
        // PKCS#8 = SEQ{ver, algId, OCTET{pkcs1}} — 2048bit 키는 앞 26바이트가 래퍼.
        val pkcs1 = pkcs8.copyOfRange(26, pkcs8.size)

        for ((label, der) in listOf("RSA PRIVATE KEY" to pkcs1, "PRIVATE KEY" to pkcs8)) {
            val pem = "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n"
            val jwt = GithubAppJwtSigner("123", pem, clockAt(10_000)).sign()
            val (h, p, s) = jwt.split('.')

            val ok = Signature.getInstance("SHA256withRSA").run {
                initVerify(kp.public)
                update("$h.$p".toByteArray())
                verify(Base64.getUrlDecoder().decode(s))
            }
            assertTrue(ok, label)
            assertEquals("""{"iat":9940,"exp":10540,"iss":"123"}""", String(Base64.getUrlDecoder().decode(p)))
        }
    }

    // ── 순서 역전 방어 ──────────────────────────────────────────
    @Test
    fun `레포 변경 - 늦게 도착한 옛 이벤트는 최신 값을 덮지 않는다`() {
        val repo = GithubRepository.of(1, 10, "acme/api", isPrivate = false)
        val t1 = Instant.parse("2026-10-27T10:00:00Z")
        val t0 = Instant.parse("2026-10-27T09:00:00Z")

        assertTrue(repo.applyChange("acme/api", isPrivate = true, updatedAt = t1))   // privatized (최신)
        assertFalse(repo.applyChange("acme/api", isPrivate = false, updatedAt = t0)) // publicized (옛 이벤트, 늦게 옴)
        assertTrue(repo.isPrivate)
    }

    // ── 설정 fail-fast ─────────────────────────────────────────
    @Test
    fun `설정 - 꺼져 있으면 시크릿 없이 통과, 켜졌는데 비었거나 미해소 placeholder 면 실패`() {
        assertTrue(GithubAppProperties(enabled = false).isConfiguredWhenEnabled())
        assertFalse(GithubAppProperties(enabled = true).isConfiguredWhenEnabled())
        val full = GithubAppProperties(
            enabled = true, appId = "1", appSlug = "ea", clientId = "c", clientSecret = "s",
            privateKeyPem = "k", webhookSecret = "w", stateSecret = "st",
        )
        assertTrue(full.isConfiguredWhenEnabled())
        assertFalse(full.copy(webhookSecret = "\${CORE_GITHUB_WEBHOOK_SECRET}").isConfiguredWhenEnabled())
    }
}
