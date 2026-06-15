package org.studieojavry.iamapi.auth.infrastructure.security

import jakarta.servlet.http.HttpServletRequest

/**
 * 발급 시점 디바이스 컨텍스트. 세션 카탈로그(`/users/me/sessions`)에 보존.
 */
data class ClientContext(
    val deviceLabel: String?,
    val userAgent: String?,
    val ipAddress: String?,
) {
    companion object {
        fun from(request: HttpServletRequest): ClientContext {
            val ua = request.getHeader("User-Agent")?.take(512)
            val ip = resolveIp(request)?.take(64)
            return ClientContext(
                deviceLabel = ua?.let { DeviceLabelParser.parse(it) },
                userAgent = ua,
                ipAddress = ip,
            )
        }

        /** X-Forwarded-For 우선(첫 IP), 없으면 remote addr. */
        private fun resolveIp(request: HttpServletRequest): String? {
            val xff = request.getHeader("X-Forwarded-For")?.takeIf { it.isNotBlank() }
            if (xff != null) return xff.split(',').first().trim()
            return request.remoteAddr
        }
    }
}

/**
 * 매우 가벼운 UA 파서 — "macOS · Chrome 132" 같은 라벨 생성. 실제 UA 파싱은 굉장히 복잡한 영역이라
 * 라이브러리(`ua-parser-jvm` 등) 도입 전엔 흔한 패턴 몇 개만 정규식으로 잡는다.
 */
internal object DeviceLabelParser {
    fun parse(ua: String): String? {
        if (ua.isBlank()) return null
        val os = detectOs(ua)
        val br = detectBrowser(ua)
        return when {
            os != null && br != null -> "$os · $br"
            os != null -> os
            br != null -> br
            else -> null
        }?.take(120)
    }

    private fun detectOs(ua: String): String? = when {
        ua.contains("Windows NT 11", true) -> "Windows 11"
        ua.contains("Windows NT 10", true) -> "Windows 10"
        ua.contains("Mac OS X", true) || ua.contains("macOS", true) -> "macOS"
        ua.contains("Android", true) -> "Android"
        ua.contains("iPhone", true) -> "iPhone"
        ua.contains("iPad", true) -> "iPad"
        ua.contains("Linux", true) -> "Linux"
        else -> null
    }

    private fun detectBrowser(ua: String): String? = when {
        ua.contains("Edg/", true) -> firstMajor(ua, Regex("Edg/(\\d+)"))?.let { "Edge $it" }
        ua.contains("Chrome/", true) && !ua.contains("Chromium/", true) ->
            firstMajor(ua, Regex("Chrome/(\\d+)"))?.let { "Chrome $it" }
        ua.contains("Firefox/", true) ->
            firstMajor(ua, Regex("Firefox/(\\d+)"))?.let { "Firefox $it" }
        ua.contains("Safari/", true) && ua.contains("Version/", true) ->
            firstMajor(ua, Regex("Version/(\\d+)"))?.let { "Safari $it" }
        ua.contains("curl/", true) -> firstMajor(ua, Regex("curl/(\\d+)"))?.let { "curl $it" }
        else -> null
    }

    private fun firstMajor(ua: String, re: Regex): String? =
        re.find(ua)?.groupValues?.getOrNull(1)
}
