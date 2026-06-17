package org.studieojavry.coreapi.shared.config

import org.springframework.beans.factory.ObjectProvider
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
 * core-api 의 인증은 게이트웨이가 보낸 internal JWT (X-Internal-Auth) 검증에 의존한다.
 *
 *  - InternalTokenAuthenticationFilter (shared-internal-auth) 가 토큰을 검증 → SecurityContext 채움
 *  - 검증된 principal 은 사용자 ID(Long) → 컨트롤러에서 @AuthenticationPrincipal 로 주입
 *  - health/fallback 외 모든 경로는 인증 필수 — 토큰 없으면 401
 *  - local 프로필 한정: `DevHeaderAuthFilter` 가 앞에 위치 — `X-Test-User-Id` 헤더로 인증 우회(테스트용)
 *
 * 메쉬 도입 후엔 토큰의 서명/iss/aud 검증을 mTLS 가 대체하므로, 이 필터는 사용자 컨텍스트
 * 추출 역할만 남기고 슬림해진다.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        internalTokenFilter: InternalTokenAuthenticationFilter,
        devHeaderAuthFilter: ObjectProvider<DevHeaderAuthFilter>,
        devCorsSource: ObjectProvider<org.springframework.web.cors.UrlBasedCorsConfigurationSource>,
        authEntryPoint: ProblemDetailAuthenticationEntryPoint,
        accessDeniedHandler: ProblemDetailAccessDeniedHandler,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { c ->
                // local profile 한정으로 DevCorsConfig 가 source 주입. 그 외 환경에선 비활성.
                devCorsSource.ifAvailable { c.configurationSource(it) }
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/error").permitAll()
                    // OpenAPI 문서 / Swagger UI — 인증 없이 열람. (실제 API 호출은 X-Internal-Auth 필요)
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // 로컬 테스트 페이지 — 정적 리소스(같은 origin). API 호출은 여전히 X-Test-User-Id 필요.
                    .requestMatchers("/comment-tester.html", "/comment-tester/**").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                // shared-error 의 enriched ProblemDetail 핸들러 — 401/403 도 모든 4xx 와 일관 응답
                it.authenticationEntryPoint(authEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter::class.java)

        // local 프로필이면 X-Test-User-Id 헤더 인증 필터를 앞에 추가(헤더 없으면 통과)
        devHeaderAuthFilter.ifAvailable { filter ->
            http.addFilterBefore(filter, InternalTokenAuthenticationFilter::class.java)
        }
        return http.build()
    }
}
