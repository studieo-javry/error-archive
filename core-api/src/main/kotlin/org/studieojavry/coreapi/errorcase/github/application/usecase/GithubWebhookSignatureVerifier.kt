package org.studieojavry.coreapi.errorcase.github.application.usecase

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `X-Hub-Signature-256: sha256=<hex>` 검증. **원본 바이트** 기준(JSON 재직렬화하면 서명이 깨짐),
 * 비교는 상수시간(`MessageDigest.isEqual`) — 타이밍으로 서명을 한 바이트씩 맞혀보는 공격 방지.
 */
object GithubWebhookSignatureVerifier {
    private const val PREFIX = "sha256="

    fun isValid(secret: String, body: ByteArray, signatureHeader: String?): Boolean {
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) return false
        val expected = sign(secret, body)
        val actual = signatureHeader.substring(PREFIX.length).lowercase().toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.isEqual(expected.toByteArray(StandardCharsets.US_ASCII), actual)
    }

    fun sign(secret: String, body: ByteArray): String =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            doFinal(body)
        }.joinToString("") { "%02x".format(it) }
}
