package org.studieojavry.publishapi.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.sharederror.security.ProblemDetailAccessDeniedHandler
import org.studieojavry.sharederror.security.ProblemDetailAuthenticationEntryPoint

/**
 * publish-api 인증
 *  - 공개 페이지 (`/p/{slug}`, `/api/v1/publishments/by-slug/{slug}/export.pdf`) 는 인증 X
 *  - 내 publish 관리 (create/preview/list/unpublish/publish/by-case) 는 internal JWT 필요 (aud=publish-api)
 *  - 401/403 은 shared-error 의 ProblemDetail 핸들러가 traceId/code/type 포함해 응답.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        internalTokenFilter: InternalTokenAuthenticationFilter,
        authEntryPoint: ProblemDetailAuthenticationEntryPoint,
        accessDeniedHandler: ProblemDetailAccessDeniedHandler,
        devHeaderAuthFilter: org.springframework.beans.factory.ObjectProvider<DevHeaderAuthFilter>,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // prod 는 base-path=/internal/actuator → 프로브가 이 경로. 인증 없이 열려야 kubelet 200.
                    .requestMatchers("/internal/actuator/health", "/internal/actuator/health/**").permitAll()
                    // Prometheus 메트릭 스크레이프 — /internal/** 은 gateway 가 외부 라우팅 안 함 → 클러스터 내부만 도달.
                    .requestMatchers("/actuator/prometheus", "/internal/actuator/prometheus").permitAll()
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // 공개 페이지 + 공개 export(pdf) — 인증 불필요
                    .requestMatchers("/p/**").permitAll()
                    .requestMatchers("/api/v1/publishments/by-slug/**").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                // shared-error 의 enriched ProblemDetail — traceId / code / type / retryable 포함.
                it.authenticationEntryPoint(authEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter::class.java)
        devHeaderAuthFilter.ifAvailable?.let {
            http.addFilterBefore(it, UsernamePasswordAuthenticationFilter::class.java)
        }
        return http.build()
    }
}
