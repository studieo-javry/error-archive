package org.studieojavry.coreapi.errorcase.shared.infrastructure.marker

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.shared.application.port.MarkerIdGeneratorPort
import java.security.SecureRandom

/**
 * 8-char hex (4 bytes 엔트로피, 약 2^32) marker.
 * `@snippet(abc12345)` / `@attach(deadbeef)` 형태로 본문에 임베드된다.
 */
@Component
class RandomMarkerIdGeneratorAdapter : MarkerIdGeneratorPort {

    private val random = SecureRandom()

    override fun generateSnippetMarkerId(): String = randomHex(8)
    override fun generateAttachmentMarkerId(): String = randomHex(8)

    private fun randomHex(length: Int): String {
        val bytes = ByteArray(length / 2 + 1)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }.take(length)
    }
}