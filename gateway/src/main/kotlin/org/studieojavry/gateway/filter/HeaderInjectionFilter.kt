package org.studieojavry.gateway.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer

/**
 * Spring Security 가 사용자 JWT 검증을 끝낸 시점에 실행된다.
 * SecurityContext 의 Jwt principal 로부터 downstream 용 **internal JWT** 를 발급하고
 * X-Internal-Auth 헤더로 주입한다.
 *
 * 과거엔 X-User-Id / X-Roles 를 평문으로 주입했으나, 평문 헤더는 8081/8080 에 직접 도달 가능한
 * 누구나 위조할 수 있었다. 이제 게이트웨이가 자기 키로 서명한 단명 토큰을 실어 보내고,
 * downstream 은 서명/aud/exp 를 검증하므로 "게이트웨이가 보낸 것"이 보장된다.
 *
 * 대상 서비스(aud)는 요청 경로로 결정한다 — RouteConfig 의 라우팅 규칙과 정합해야 한다.
 * permitAll 경로(로그인/갱신 등)는 SecurityContext 에 Jwt 가 없으므로 토큰 없이 통과한다.
 */
@Component
class HeaderInjectionFilter(
    private val internalTokenIssuer: InternalTokenIssuer,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = SecurityContextHolder.getContext().authentication?.principal

        if (principal is Jwt && principal.subject != null) {
            val token = internalTokenIssuer.issue(
                subject = principal.subject,
                audience = resolveAudience(request.requestURI),
                roles = principal.getClaimAsStringList("roles").orEmpty(),
            )
            val wrapped = HeaderMutatingRequestWrapper(request).apply {
                putHeader(InternalTokenAuthenticationFilter.HEADER_NAME, token)
            }
            filterChain.doFilter(wrapped, response)
        } else {
            filterChain.doFilter(request, response)
        }
    }

    private fun resolveAudience(uri: String): String = when {
        // noti-api 가 *서버* 인 경로 — 우선순위 가장 높음 (users/me 의 일부라 iam-api 보다 먼저 매칭).
        uri.startsWith("/api/v1/users/me/notification-settings") -> AUDIENCE_NOTI_API
        uri.startsWith("/api/v1/users/me/notifications") -> AUDIENCE_NOTI_API
        uri.startsWith("/api/v1/users/me/device-tokens") -> AUDIENCE_NOTI_API
        // insight-api (잔디) — /me 또는 /{userId}/activity-grass
        uri.contains("/activity-grass") -> AUDIENCE_INSIGHT_API
        // publish-api — /api/v1/publishments/* (인증 필요) + /p/* (인증 X 이지만 토큰 있어도 무해)
        uri.startsWith("/api/v1/publishments") || uri.startsWith("/p/") -> AUDIENCE_PUBLISH_API
        // core-api 가 *서버* 인 모든 경로. 새 endpoint 추가 시 여기 갱신 필수
        // (안 그러면 aud=iam-api 로 토큰 발급되어 core-api 가 401 반환).
        uri.startsWith("/api/v1/error-cases") ||
            uri.startsWith("/api/v1/error-attachments") ||
            uri.startsWith("/api/v1/error-snippets") ||
            uri.startsWith("/api/v1/step-attempt-types") -> AUDIENCE_CORE_API
        else -> AUDIENCE_IAM_API
    }

    companion object {
        const val AUDIENCE_CORE_API = "core-api"
        const val AUDIENCE_IAM_API = "iam-api"
        const val AUDIENCE_NOTI_API = "noti-api"
        const val AUDIENCE_INSIGHT_API = "insight-api"
        const val AUDIENCE_PUBLISH_API = "publish-api"
    }
}
