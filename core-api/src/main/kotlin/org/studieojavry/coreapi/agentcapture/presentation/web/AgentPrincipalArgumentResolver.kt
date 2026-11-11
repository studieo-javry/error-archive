package org.studieojavry.coreapi.agentcapture.presentation.web

import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.internalauth.InternalAuthentication

/**
 * 컨트롤러의 [AgentPrincipal] 인자를 SecurityContext 의 [InternalAuthentication] 에서 조립한다.
 * connectionId 가 없으면(사용자 토큰) agent 경로를 호출할 자격이 아니므로 401 로 거부한다.
 */
class AgentPrincipalArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == AgentPrincipal::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): AgentPrincipal {
        val auth = SecurityContextHolder.getContext().authentication as? InternalAuthentication
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "agent authentication required")
        val connectionId = auth.connectionId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "not an agent connection token")
        return AgentPrincipal(
            userId = auth.principal as Long,
            connectionId = connectionId,
            scopes = auth.scopes.toSet(),
        )
    }
}
