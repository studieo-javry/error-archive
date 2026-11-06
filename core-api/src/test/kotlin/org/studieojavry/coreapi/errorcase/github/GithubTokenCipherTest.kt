package org.studieojavry.coreapi.errorcase.github

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import org.studieojavry.coreapi.errorcase.github.infrastructure.crypto.GithubTokenCipher
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AES-256-GCM 토큰 암복호화 — 왕복·IV 비재사용·변조 탐지. */
class GithubTokenCipherTest {

    private val cipher = GithubTokenCipher(
        GithubAppProperties(tokenEncryptionKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })),
    )

    @Test
    fun `encrypt then decrypt round-trips`() {
        val plaintext = "ghu_abcDEF1234567890"
        val encrypted = cipher.encrypt(plaintext)
        assertEquals(plaintext, cipher.decrypt(encrypted))
        // DB 에 들어가는 건 평문이 아니다.
        assertFalse(String(encrypted.ciphertext, Charsets.ISO_8859_1).contains(plaintext))
        assertEquals(12, encrypted.iv.size) // GCM 표준 IV 12바이트
    }

    @Test
    fun `same plaintext yields different iv and ciphertext each time`() {
        val a = cipher.encrypt("same-token")
        val b = cipher.encrypt("same-token")
        assertFalse(a.iv.contentEquals(b.iv)) // IV 재사용 금지
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
        assertEquals("same-token", cipher.decrypt(a))
        assertEquals("same-token", cipher.decrypt(b))
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val encrypted = cipher.encrypt("secret")
        val tampered = encrypted.copy(ciphertext = encrypted.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertThrows<javax.crypto.AEADBadTagException> { cipher.decrypt(tampered) }
    }

    @Test
    fun `wrong key cannot decrypt`() {
        val encrypted = cipher.encrypt("secret")
        val other = GithubTokenCipher(
            GithubAppProperties(tokenEncryptionKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })),
        )
        val failed = runCatching { other.decrypt(encrypted) }.isFailure
        assertTrue(failed)
    }
}
