package org.studieojavry.gateway.filter

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer
import org.studieojavry.sharederror.problem.ProblemDetailBuilder
import org.studieojavry.sharederror.security.ProblemDetailAuthenticationEntryPoint
import org.studieojavry.sharederror.trace.TraceIdAccessor
import tools.jackson.databind.ObjectMapper

/**
 * agent 경로(api/v1/agent 하위) 전용 — opaque agent 토큰(Authorization: Bearer eaa_...)을 iam
 * introspection 으로 검증한 뒤, downstream(core-api)용 내부 RS256 JWT 를 발급해 X-Internal-Auth 로 주입한다.
 *
 * 사용자 JWT 체인과 완전히 분리된 별도 SecurityFilterChain 에서만 실행된다(HS256 검증을 거치지 않음).
 * 외부에서 들어온 X-Internal-Auth 는 wrapper 가 덮어써 신뢰하지 않는다.
 *
 *  - 토큰 없음/형식 불일치/invalid → 401 (downstream 도달 X)
 *  - iam 도달 실패/5xx → 503 + Retry-After (토큰을 통과시키지 않음, 커넥터가 백오프·재시도)
 *  - 성공 → sub=userId, cid=connectionId, scp=scopes 내부 JWT(aud=core-api) 주입
 *
 * 실패 응답에 `response.sendError` 를 쓰지 않는다 — sendError 는 서블릿 ERROR 디스패치(/error)를
 * 유발하고, 그 디스패치가 기본 보안 체인(anyRequest().authenticated())에 막혀 의도한 상태코드
 * (특히 503)를 401 로 덮어쓰기 때문. 대신 체인에 연결된 엔트리포인트에 위임(401)하거나
 * ProblemDetail 을 직접 기록(503)해 ERROR 디스패치 없이 정확한 상태·바디를 반환한다.
 */
class AgentTokenAuthenticationFilter(
    private val introspectionClient: AgentIntrospectionClient,
    private val internalTokenIssuer: InternalTokenIssuer,
    private val authEntryPoint: ProblemDetailAuthenticationEntryPoint,
    private val objectMapper: ObjectMapper,
    private val traceIdAccessor: TraceIdAccessor,
) : OncePerRequestFilter() {

    private val logger = KotlinLogging.logger {}

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawToken = bearerToken(request)
        if (rawToken == null || !rawToken.startsWith(AGENT_TOKEN_PREFIX)) {
            authEntryPoint.commence(request, response, InsufficientAuthenticationException("Missing or malformed agent token"))
            return
        }

        val principal = try {
            introspectionClient.introspect(rawToken, clientDescriptor(request))
        } catch (ex: Exception) {
            logger.warn { "agent introspection unavailable: ${ex.message}" }
            writeServiceUnavailable(request, response)
            return
        }
        if (principal == null) {
            authEntryPoint.commence(request, response, InvalidBearerTokenException("Invalid agent token"))
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

    /**
     * iam introspection 에 도달하지 못했을 때(5xx·타임아웃·연결 실패) — 토큰 유효성을 "확인 불가"다.
     * 401(자격증명 무효)이 아니라 503 으로 응답해, 커넥터가 일시 장애로 보고 백오프·재시도하게 한다
     * (401 로 주면 멀쩡한 토큰을 auth 실패로 오분류). Retry-After 로 재시도 시점을 안내한다.
     */
    private fun writeServiceUnavailable(request: HttpServletRequest, response: HttpServletResponse) {
        val traceId = traceIdAccessor.currentOrNew()
        response.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString())
        response.setHeader("X-Trace-Id", traceId)
        val problem = ProblemDetailBuilder.build(
            status = HttpStatus.SERVICE_UNAVAILABLE,
            detail = "Authorization service temporarily unavailable. Please retry.",
            code = "AUTH_SERVICE_UNAVAILABLE",
            traceId = traceId,
            retryable = true,
            instance = request.requestURI,
            extra = mapOf("retryAfterSeconds" to RETRY_AFTER_SECONDS),
        )
        objectMapper.writeValue(response.outputStream, problem)
    }

    private fun bearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BEARER_PREFIX)) return null
        return header.removePrefix(BEARER_PREFIX).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 커넥터가 신고한 설치 메타(표시용). 전부 없으면 null 로 전달(구형 커넥터 후방호환).
     * 이 값은 iam 에서 표시·그룹에만 쓰이고 인가 판단엔 쓰이지 않는다(위조 가능한 자기신고).
     */
    private fun clientDescriptor(request: HttpServletRequest): AgentIntrospectionClient.ClientDescriptor? {
        fun h(name: String) = request.getHeader(name)?.trim()?.takeIf { it.isNotEmpty() }
        val kind = h(HEADER_CLIENT_KIND)
        val version = h(HEADER_CLIENT_VERSION)
        val installId = h(HEADER_INSTALL_ID)
        val installLabel = h(HEADER_INSTALL_LABEL)
        if (kind == null && version == null && installId == null && installLabel == null) return null
        return AgentIntrospectionClient.ClientDescriptor(kind, version, installId, installLabel)
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        const val AGENT_TOKEN_PREFIX = "eaa_"
        const val HEADER_CLIENT_KIND = "X-Agent-Client-Kind"
        const val HEADER_CLIENT_VERSION = "X-Agent-Client-Version"
        const val HEADER_INSTALL_ID = "X-Agent-Install-Id"
        const val HEADER_INSTALL_LABEL = "X-Agent-Install-Label"
        private const val RETRY_AFTER_SECONDS = 30
    }
}
