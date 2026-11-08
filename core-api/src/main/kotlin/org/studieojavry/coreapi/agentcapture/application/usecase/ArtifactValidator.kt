package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.agentcapture.domain.model.UnsupportedArtifactException
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * P0 허용 형식만 통과시킨다: PNG/JPEG/WebP, UTF-8 텍스트·로그·diff, JSON.
 * SVG/HTML/실행 파일/압축 파일은 거부한다. 선언된 MIME 뿐 아니라 실제 바이트(매직/인코딩)를 검사해
 * 이름만 바꾼 파일을 막는다. JSON·텍스트는 안전하게 저장하되 실행하지 않는다.
 */
@Component
class ArtifactValidator(
    private val objectMapper: ObjectMapper,
) {
    /** 통과 시 정규화된 content type 반환, 아니면 UnsupportedArtifactException(→415). */
    fun validate(declaredContentType: String, bytes: ByteArray): String {
        if (bytes.isEmpty()) throw UnsupportedArtifactException("empty file")
        val ct = declaredContentType.substringBefore(';').trim().lowercase()
        return when (ct) {
            "image/png" -> ct.also { requirePng(bytes) }
            "image/jpeg" -> ct.also { requireJpeg(bytes) }
            "image/webp" -> ct.also { requireWebp(bytes) }
            "text/plain" -> ct.also { requireSafeText(bytes) }
            "application/json" -> ct.also { requireJson(bytes) }
            else -> throw UnsupportedArtifactException("unsupported content type: $ct (allowed: PNG/JPEG/WebP, text/plain, application/json)")
        }
    }

    private fun requirePng(b: ByteArray) {
        if (!b.startsWith(PNG_MAGIC)) throw UnsupportedArtifactException("not a PNG (magic mismatch)")
    }

    private fun requireJpeg(b: ByteArray) {
        if (!b.startsWith(JPEG_MAGIC)) throw UnsupportedArtifactException("not a JPEG (magic mismatch)")
    }

    private fun requireWebp(b: ByteArray) {
        // RIFF....WEBP
        val ok = b.size >= 12 && b.startsWith("RIFF".toByteArray(Charsets.US_ASCII)) &&
            b.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII))
        if (!ok) throw UnsupportedArtifactException("not a WebP (magic mismatch)")
    }

    private fun requireSafeText(b: ByteArray) {
        val text = decodeUtf8(b)
        val head = text.trimStart().take(256).lowercase()
        if (MARKUP_PREFIXES.any { head.startsWith(it) }) {
            throw UnsupportedArtifactException("HTML/SVG/XML markup is not allowed as a text artifact")
        }
    }

    private fun requireJson(b: ByteArray) {
        val text = decodeUtf8(b)
        try {
            objectMapper.readTree(text)
        } catch (e: Exception) {
            throw UnsupportedArtifactException("not valid JSON: ${e.message}")
        }
    }

    private fun decodeUtf8(b: ByteArray): String =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b))
                .toString()
        } catch (e: Exception) {
            throw UnsupportedArtifactException("not valid UTF-8 text")
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    companion object {
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val MARKUP_PREFIXES = listOf("<svg", "<?xml", "<!doctype html", "<html")
    }
}
