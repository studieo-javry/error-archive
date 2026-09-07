package org.studieojavry.publishapi.shared.config

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
 * gateway 없이 publish-api 를 직접 호출해 e2e 검증할 때 사용.
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
