package org.studieojavry.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.studieojavry.gateway.filter.HeaderInjectionFilter

/**
 * 게이트웨이의 인증 책임은 Spring Security 의 oauth2ResourceServer 에 위임한다.
 *
 *  - permitAll 경로(로그인 / 토큰 갱신 / health / fallback): JWT 검증 skip
 *  - 그 외 모든 경로: BearerTokenAuthenticationFilter 가 JWT 검증 → SecurityContext 채움
 *  - HeaderInjectionFilter: SecurityContext → X-User-Id / X-Roles 헤더로 변환 (downstream 용)
 *
 * 검증 실패 시 HttpStatusEntryPoint 가 401 반환 — downstream 호출 전 차단.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        headerInjectionFilter: HeaderInjectionFilter
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/api/v1/auth/oauth/**",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/actuator/health",
                    "/__fallback/**",
                    // 정적 아바타 이미지 — 공개 read (이미지 fetch 에 Authorization 안 가도록)
                    "/avatars/**",
                    // Swagger UI(aggregator) + 게이트웨이 자체 docs + 다운스트림 docs 프록시 경로
                    "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/api-docs/**"
                ).permitAll()
                .anyRequest().authenticated()
            }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .oauth2ResourceServer { rs -> rs.jwt { it.decoder(jwtDecoder) } }
            .addFilterAfter(headerInjectionFilter, BearerTokenAuthenticationFilter::class.java)
        return http.build()
    }
}
