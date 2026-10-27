package org.studieojavry.coreapi.errorcase.github.infrastructure.api

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64

/**
 * GitHub App JWT(RS256) 서명 — installation 토큰 발급과 `/app/...` 호출용. 수명 9분(GitHub 상한 10분).
 *
 * 라이브러리 없이 직접 서명하는 이유: core-api 는 JOSE 라이브러리를 직접 의존하지 않고(shared-internal-auth 내부 의존),
 * App JWT 는 헤더/클레임이 고정이라 표준 `Signature(SHA256withRSA)` 로 충분하다.
 *
 * GitHub 가 내려주는 키는 **PKCS#1**(`BEGIN RSA PRIVATE KEY`)이라 JDK 가 바로 못 읽는다 →
 * PKCS#8 래퍼(AlgorithmIdentifier rsaEncryption)를 씌워 읽는다. PKCS#8 PEM 도 그대로 허용.
 */
class GithubAppJwtSigner(
    private val appId: String,
    privateKeyPem: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val privateKey: PrivateKey = parsePrivateKey(privateKeyPem)

    fun sign(): String {
        val now = clock.instant().epochSecond
        // iat 60초 뒤로 — GitHub 서버와의 시계 오차 허용(공식 권장).
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val payload = """{"iat":${now - 60},"exp":${now + 9 * 60},"iss":"$appId"}"""
        val signingInput = b64(header.toByteArray(StandardCharsets.UTF_8)) + "." + b64(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return signingInput + "." + b64(signature)
    }

    companion object {
        private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
        private fun b64(bytes: ByteArray): String = URL_ENCODER.encodeToString(bytes)

        fun parsePrivateKey(pem: String): PrivateKey {
            val pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY")
            val der = Base64.getMimeDecoder().decode(
                pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString(""),
            )
            val pkcs8 = if (pkcs1) wrapPkcs1(der) else der
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        }

        /** PKCS#1 RSAPrivateKey → PKCS#8 PrivateKeyInfo { version 0, rsaEncryption, OCTET STRING(pkcs1) }. */
        private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            val version = byteArrayOf(0x02, 0x01, 0x00)
            // SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }
            val algorithm = byteArrayOf(
                0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
                0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
            )
            val octet = byteArrayOf(0x04) + derLength(pkcs1.size) + pkcs1
            val body = version + algorithm + octet
            return byteArrayOf(0x30) + derLength(body.size) + body
        }

        private fun derLength(len: Int): ByteArray = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
            else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
        }
    }
}
