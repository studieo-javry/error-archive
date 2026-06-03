package org.studieojavry.coreapi.shared.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Profile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.studieojavry.internalauth.InternalAuthentication

/**
 * **로컬 테스트 전용** — `X-Test-User-Id` 헤더로 인증 우회. *local 프로필에서만* 활성.
 *
 * 게이트웨이를 띄우지 않고 브라우저(comment-tester.html 등)에서 직접 core-api 를 호출할 때 쓰기 위함.
 * 헤더 부재 시 통과 — `InternalTokenAuthenticationFilter` 의 정상 경로(X-Internal-Auth) 도 그대로 동작.
 *
 * **운영 위험**: dev/stg/prod 에서 활성화되면 누구나 임의 user id 로 인증 가능 → @Profile("local") 한정.
 */
@Component
@Profile("local")
class DevHeaderAuthFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val testUserId = request.getHeader(HEADER_NAME)?.toLongOrNull()
        if (testUserId != null && SecurityContextHolder.getContext().authentication == null) {
            val auth = InternalAuthentication(
                userId = testUserId,
                authorities = emptyList(),
                callerService = "dev-tester",
            )
            val ctx = SecurityContextHolder.createEmptyContext()
            ctx.authentication = auth
            SecurityContextHolder.setContext(ctx)
            try {
                filterChain.doFilter(request, response)
            } finally {
                SecurityContextHolder.clearContext()
            }
            return
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val HEADER_NAME = "X-Test-User-Id"
    }
}
