package org.studieojavry.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.studieojavry.gateway.filter.AgentIntrospectionClient
import org.studieojavry.gateway.filter.AgentTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer
import org.studieojavry.sharederror.security.ProblemDetailAccessDeniedHandler
import org.studieojavry.sharederror.security.ProblemDetailAuthenticationEntryPoint
import org.studieojavry.sharederror.trace.TraceIdAccessor
import tools.jackson.databind.ObjectMapper

/**
 * agent 전용 인증 체인. agent 경로(api/v1/agent 하위)만 담당하며(securityMatcher), 사용자 HS256
 * JWT 를 검증하는 기본 체인(SecurityConfig)보다 높은 우선순위(@Order(1))로 먼저 매칭된다.
 *
 * 인바운드 자격증명이 opaque agent 토큰이라 oauth2ResourceServer(JWT)를 쓰지 않고,
 * AgentTokenAuthenticationFilter 가 iam introspection → 내부 JWT 발급을 전담한다.
 */
@Configuration
class AgentSecurityConfig {

    @Bean
    @Order(1)
    fun agentSecurityFilterChain(
        http: HttpSecurity,
        introspectionClient: AgentIntrospectionClient,
        internalTokenIssuer: InternalTokenIssuer,
        authEntryPoint: ProblemDetailAuthenticationEntryPoint,
        accessDeniedHandler: ProblemDetailAccessDeniedHandler,
        objectMapper: ObjectMapper,
        traceIdAccessor: TraceIdAccessor,
    ): SecurityFilterChain {
        // Filter 빈으로 등록하면 Boot 가 서블릿 체인 전역에 자동 등록하므로, 이 체인에만 수동 주입한다.
        val agentFilter = AgentTokenAuthenticationFilter(
            introspectionClient, internalTokenIssuer, authEntryPoint, objectMapper, traceIdAccessor,
        )
        http
            .securityMatcher("/api/v1/agent/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .exceptionHandling {
                it.authenticationEntryPoint(authEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .addFilterBefore(agentFilter, AuthorizationFilter::class.java)
        return http.build()
    }
}
