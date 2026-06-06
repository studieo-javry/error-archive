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
        uri.startsWith("/api/v1/error-cases") ||
                uri.startsWith("/api/v1/error-attachments") ||
                uri.startsWith("/api/v1/error-snippets") -> AUDIENCE_CORE_API
        else -> AUDIENCE_IAM_API
    }

    companion object {
        const val AUDIENCE_CORE_API = "core-api"
        const val AUDIENCE_IAM_API = "iam-api"
    }
}
