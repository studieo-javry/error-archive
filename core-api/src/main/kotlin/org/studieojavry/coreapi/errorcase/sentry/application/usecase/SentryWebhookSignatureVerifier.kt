package org.studieojavry.coreapi.errorcase.sentry.application.usecase

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sentry `Sentry-Hook-Signature` 검증 — 본문의 HMAC-SHA256(hex), client secret 키.
 * **원본 바이트** 기준(JSON 재직렬화하면 깨짐), 비교는 상수시간(`MessageDigest.isEqual`).
 * github 서명 검증과 달리 `sha256=` 접두어가 없다(hex 만).
 */
object SentryWebhookSignatureVerifier {

    fun isValid(secret: String, body: ByteArray, signatureHeader: String?): Boolean {
        if (signatureHeader.isNullOrBlank()) return false
        val expected = sign(secret, body).toByteArray(StandardCharsets.US_ASCII)
        val actual = signatureHeader.trim().lowercase().toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.isEqual(expected, actual)
    }

    fun sign(secret: String, body: ByteArray): String =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            doFinal(body)
        }.joinToString("") { "%02x".format(it) }
}
