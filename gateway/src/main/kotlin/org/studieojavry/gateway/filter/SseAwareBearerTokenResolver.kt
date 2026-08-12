package org.studieojavry.gateway.filter

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.stereotype.Component

/**
 * 기본 `DefaultBearerTokenResolver` (Authorization header) 를 우선 사용하고,
 * **SSE path 에 한해서** `?ticket=` query 도 fallback 으로 추출한다.
 *
 * 배경: `EventSource` API 는 Authorization header 를 보낼 수 없다. SSE 만 우회 채널로
 * URL query 의 단명 ticket (typ=sse) 을 사용. 다른 endpoint 는 *Authorization header 만* —
 * typ=sse 토큰을 query 로 보내봐야 추출되지 않아 401.
 *
 * 격리 약점: typ=sse 토큰을 *Authorization: Bearer* 로 보내면 다른 endpoint 도 통과한다
 * (jwtDecoder 의 typValidator 가 access | sse 둘 다 허용). 단명 ttl (default 30s) 로 mitigated.
 * 완전 격리는 path 별 typ 검증 (`SecurityFilterChain` 에서 SSE path 만 typ=sse 허용) 후속 강화.
 */
@Component
class SseAwareBearerTokenResolver : BearerTokenResolver {

    private val default = DefaultBearerTokenResolver()

    override fun resolve(request: HttpServletRequest): String? {
        // 1. Authorization header 우선 — 표준 흐름 (일반 API)
        default.resolve(request)?.let { return it }

        // 2. SSE path 에 한해서 ?ticket= fallback
        if (isSseEndpoint(request.requestURI)) {
            return request.getParameter("ticket")?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun isSseEndpoint(uri: String): Boolean =
        uri.endsWith("/stream") || uri.contains("/stream?") || uri.contains("/stream;")
}
