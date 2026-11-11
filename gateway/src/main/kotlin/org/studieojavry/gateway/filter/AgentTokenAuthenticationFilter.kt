package org.studieojavry.gateway.filter

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer

/**
 * agent 경로(api/v1/agent 하위) 전용 — opaque agent 토큰(Authorization: Bearer eaa_...)을 iam
 * introspection 으로 검증한 뒤, downstream(core-api)용 내부 RS256 JWT 를 발급해 X-Internal-Auth 로 주입한다.
 *
 * 사용자 JWT 체인과 완전히 분리된 별도 SecurityFilterChain 에서만 실행된다(HS256 검증을 거치지 않음).
 * 외부에서 들어온 X-Internal-Auth 는 wrapper 가 덮어써 신뢰하지 않는다.
 *
 *  - 토큰 없음/형식 불일치/invalid → 401 (downstream 도달 X)
 *  - iam 도달 실패 → 503 (토큰을 통과시키지 않음)
 *  - 성공 → sub=userId, cid=connectionId, scp=scopes 내부 JWT(aud=core-api) 주입
 */
class AgentTokenAuthenticationFilter(
    private val introspectionClient: AgentIntrospectionClient,
    private val internalTokenIssuer: InternalTokenIssuer,
) : OncePerRequestFilter() {

    private val logger = KotlinLogging.logger {}

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawToken = bearerToken(request)
        if (rawToken == null || !rawToken.startsWith(AGENT_TOKEN_PREFIX)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or malformed agent token")
            return
        }

        val principal = try {
            introspectionClient.introspect(rawToken)
        } catch (ex: Exception) {
            logger.warn { "agent introspection unavailable: ${ex.message}" }
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Authorization service unavailable")
            return
        }
        if (principal == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid agent token")
            return
        }

        val internalToken = internalTokenIssuer.issue(
            subject = principal.userId.toString(),
            audience = HeaderInjectionFilter.AUDIENCE_CORE_API,
            roles = emptyList(),
            connectionId = principal.connectionId,
            scopes = principal.scopes,
        )
        val wrapped = HeaderMutatingRequestWrapper(request).apply {
            putHeader(InternalTokenAuthenticationFilter.HEADER_NAME, internalToken)
        }

        val authorities = principal.scopes.map { SimpleGrantedAuthority("SCOPE_$it") }
        val authentication = PreAuthenticatedAuthenticationToken(principal.userId, rawToken, authorities)
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        try {
            filterChain.doFilter(wrapped, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun bearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BEARER_PREFIX)) return null
        return header.removePrefix(BEARER_PREFIX).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        const val AGENT_TOKEN_PREFIX = "eaa_"
    }
}
