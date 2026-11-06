package org.studieojavry.coreapi.demo.presentation

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.studieojavry.coreapi.demo.application.DemoGuestWritePolicy

/**
 * 게스트의 케이스 하위 변경 요청(`/api/v1/error-cases/{id}/...` 의 POST·PUT·PATCH·DELETE)을 데모 WS 케이스로 제한.
 * 댓글·스텝·솔루션·me-too·watchlist·태그·GitHub 링크·케이스 수정/삭제가 모두 이 경로 아래에 있다.
 *
 *  - 숫자가 아닌 하위 경로(`from-github-issue` 등)는 게스트에게 열지 않는다.
 *  - 케이스 생성(`POST /api/v1/error-cases`)은 본문의 workspaceId 가 필요해 컨트롤러에서 검사한다.
 */
@Component
class DemoGuestWriteInterceptor(
    private val policy: DemoGuestWritePolicy,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (HttpMethod.valueOf(request.method) !in MUTATING) return true
        if (!policy.isGuest(SecurityContextHolder.getContext().authentication)) return true

        val rest = request.requestURI.removePrefix(request.contextPath).removePrefix(BASE).trimStart('/')
        if (rest.isEmpty()) return true   // 케이스 생성 — 컨트롤러가 workspaceId 로 판정
        val caseId = rest.substringBefore('/').toLongOrNull()
        if (caseId == null || !policy.canWriteCase(caseId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "guests can only modify demo workspace cases")
        }
        return true
    }

    private companion object {
        const val BASE = "/api/v1/error-cases"
        val MUTATING = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
    }
}

@Configuration
class DemoGuestWriteConfig(
    private val interceptor: DemoGuestWriteInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/error-cases", "/api/v1/error-cases/**")
    }
}
