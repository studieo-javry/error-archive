package org.studieojavry.publishapi.publishment.application.usecase

import java.util.Base64

/**
 * 내 발행물 목록 keyset 커서 인코딩.
 *
 * `"<정렬키값>|<id>"` 를 base64(url-safe) 로 감싼 opaque 문자열.
 * 정렬키값은 정렬 기준에 따라 ISO LocalDateTime 또는 Long(viewCount) 의 문자열.
 * (`|` 는 두 값 어디에도 안 나오므로 구분자로 안전.)
 */
object PublishmentCursor {

    fun encode(sortValue: String, id: Long): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$sortValue|$id".toByteArray(Charsets.UTF_8))

    /** 잘못된 커서는 null → 호출자가 첫 페이지로 취급. */
    fun decode(cursor: String): Decoded? = try {
        val raw = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
        val sep = raw.lastIndexOf('|')
        if (sep <= 0) null
        else {
            val value = raw.substring(0, sep)
            val id = raw.substring(sep + 1).toLong()
            Decoded(value, id)
        }
    } catch (e: Exception) {
        null
    }

    data class Decoded(val value: String, val id: Long)
}
