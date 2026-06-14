package org.studieojavry.notiapi.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter

/**
 * noti-api 인증 — 게이트웨이가 보낸 internal JWT(X-Internal-Auth) 검증.
 *
 * - `InternalTokenAuthenticationFilter` 가 토큰 검증 → SecurityContext 채움
 * - principal = userId (Long) — 컨트롤러가 `@AuthenticationPrincipal` 로 주입받음
 * - health/openapi/error 외 모든 경로는 인증 필수
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        internalTokenFilter: InternalTokenAuthenticationFilter,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // 모든 인증된 호출 — internal JWT (X-Internal-Auth, aud=noti-api).
                    // /internal/** 도 동일 인증 사용 (gateway 가 라우팅하지 않으므로 외부 접근 X).
                    .anyRequest().authenticated()
            }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}