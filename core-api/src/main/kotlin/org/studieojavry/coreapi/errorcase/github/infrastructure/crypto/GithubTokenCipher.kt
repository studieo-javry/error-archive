package org.studieojavry.coreapi.errorcase.github.infrastructure.crypto

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 저장용 GitHub 사용자 토큰 AES-256-GCM 암복호화. 토큰은 사용자 자원 접근권을 쥔 비밀이라 평문 저장 금지.
 *
 * - 키: [GithubAppProperties.tokenEncryptionKey] (Base64 32바이트). properties 검증이 길이를 보증하므로 여기선 신뢰.
 * - IV: 매 암호화마다 12바이트 난수(GCM 표준). **IV 재사용 금지** — 같은 키로 IV 가 겹치면 GCM 안전성이 깨진다.
 * - 태그: 128비트(인증). 복호화 시 변조가 있으면 예외.
 * - IV 와 ciphertext 는 분리 컬럼으로 저장(IV 는 비밀이 아님). 복호화에 둘 다 필요.
 */
@Component
@ConditionalOnGithubEnabled
class GithubTokenCipher(properties: GithubAppProperties) {

    private val key = SecretKeySpec(Base64.getDecoder().decode(properties.tokenEncryptionKey!!.trim()), "AES")
    private val random = SecureRandom()

    data class Encrypted(val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(plaintext: String): Encrypted {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return Encrypted(iv, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(encrypted: Encrypted): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, encrypted.iv))
        }
        return String(cipher.doFinal(encrypted.ciphertext), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
