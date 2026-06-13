package org.studieojavry.notiapi.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.sharederror.security.ProblemDetailAccessDeniedHandler
import org.studieojavry.sharederror.security.ProblemDetailAuthenticationEntryPoint

/**
 * noti-api 인증 — 게이트웨이가 보낸 internal JWT(X-Internal-Auth) 검증.
 *
 * - `InternalTokenAuthenticationFilter` 가 토큰 검증 → SecurityContext 채움
 * - principal = userId (Long) — 컨트롤러가 `@AuthenticationPrincipal` 로 주입받음
 * - health/openapi/error 외 모든 경로는 인증 필수
 *
 * local 한정 완화: `/__dev/` (dev fixture controller) + CORS allow-all — SSE 검증/playground 호환.
 * dev/stg/prod 는 영향 없음 (isLocal=false).
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        internalTokenFilter: InternalTokenAuthenticationFilter,
        environment: Environment,
        authEntryPoint: ProblemDetailAuthenticationEntryPoint,
        accessDeniedHandler: ProblemDetailAccessDeniedHandler,
    ): SecurityFilterChain {
        val isLocal = environment.activeProfiles.contains("local") ||
            (environment.activeProfiles.isEmpty() && environment.defaultProfiles.contains("local"))

        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                if (isLocal) {
                    auth.requestMatchers("/__dev/**").permitAll()
                }
                auth.requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // 모든 인증된 호출 — internal JWT (X-Internal-Auth, aud=noti-api).
                    // /internal/** 도 동일 인증 사용 (gateway 가 라우팅하지 않으므로 외부 접근 X).
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                // shared-error 의 enriched ProblemDetail — traceId / code / type / retryable 포함.
                it.authenticationEntryPoint(authEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    /**
     * CORS — local 한정 모든 origin 허용 (playground HTML 이 `file://` 또는 `http://localhost:*` 에서
     * SSE 호출하는 케이스 cover). dev/stg/prod 는 빈 config = 모두 거부 → gateway 경유 강제.
     */
    @Bean
    fun corsConfigurationSource(environment: Environment): CorsConfigurationSource {
        val isLocal = environment.activeProfiles.contains("local") ||
            (environment.activeProfiles.isEmpty() && environment.defaultProfiles.contains("local"))
        val config = CorsConfiguration().apply {
            if (isLocal) {
                addAllowedOriginPattern("*")
                addAllowedMethod("*")
                addAllowedHeader("*")
                allowCredentials = false
            }
        }
        // *dev fixture (`/__dev/**`) 만* CORS 응답. production endpoint (`/api/v1/...`) 는 gateway 가
        // 책임 — noti-api 도 응답하면 헤더 중복으로 browser 가 차단 (실 발견: Origin: null 일 때 헤더 2번).
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/__dev/**", config)
        }
    }
}
