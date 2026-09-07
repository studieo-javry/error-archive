package org.studieojavry.publishapi.publishment.domain

import java.security.SecureRandom

/**
 * URL-safe base62 슬러그 발급. 충돌은 호출자가 *DB UNIQUE 위반 catch + 재시도* 로 처리.
 *
 * 8자리: 62^8 = 218조 — 일상적 충돌은 무시 가능.
 */
object SlugGenerator {
    private val ALPHABET =
        ('0'..'9') + ('a'..'z') + ('A'..'Z')  // 62 chars
    private val rnd = SecureRandom()

    fun generate(length: Int = 8): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.size)]) }
        return sb.toString()
    }
}
