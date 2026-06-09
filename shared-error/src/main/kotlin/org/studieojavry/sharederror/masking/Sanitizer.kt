package org.studieojavry.sharederror.masking

/**
 * 모든 환경에서 자동 마스킹 — 절대 경로 / SQL 패턴 / email 등 PII.
 * 외부 노출되면 위험한 값들을 정규식 기반으로 토큰 치환.
 */
object Sanitizer {
    private val ABSOLUTE_PATH = Regex("(?:/(?:Users|var|home|opt|etc)/[^\\s,'\"]+)")
    private val SQL_LIKE = Regex(
        "(?i)(?:select|insert|update|delete|create|alter|drop|truncate)\\s+[\\s\\S]+?(?:from|into|set|table)\\s+\\w+"
    )
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    fun sanitize(input: String?): String? {
        if (input == null) return null
        var out = input
        out = ABSOLUTE_PATH.replace(out, "<path>")
        out = SQL_LIKE.replace(out, "<sql>")
        out = EMAIL.replace(out, "<email>")
        return out
    }
}
