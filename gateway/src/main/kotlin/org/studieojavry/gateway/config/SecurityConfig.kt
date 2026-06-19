package org.studieojavry.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.studieojavry.gateway.filter.HeaderInjectionFilter
import org.studieojavry.gateway.filter.SseAwareBearerTokenResolver
import org.studieojavry.sharederror.security.ProblemDetailAccessDeniedHandler
import org.studieojavry.sharederror.security.ProblemDetailAuthenticationEntryPoint

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
        headerInjectionFilter: HeaderInjectionFilter,
        sseAwareBearerTokenResolver: SseAwareBearerTokenResolver,
        authEntryPoint: ProblemDetailAuthenticationEntryPoint,
        accessDeniedHandler: ProblemDetailAccessDeniedHandler,
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
                    // health probe — local(/actuator) + prod(base-path=/internal/actuator) 둘 다.
                    "/actuator/health", "/actuator/health/**",
                    "/internal/actuator/health", "/internal/actuator/health/**",
                    "/__fallback/**",
                    // 정적 아바타 이미지 — 공개 read (이미지 fetch 에 Authorization 안 가도록)
                    "/avatars/**",
                    // 공개 publish 페이지 + 공개 export (인증 X — 누구나 열람/다운로드)
                    "/p/**", "/api/v1/publishments/by-slug/**",
                    // I3: 공개 프로필 잔디 — 잔디 정책 v0.2 "전부 공개". 로그아웃 상태에서도 열람 가능해야 함.
                    //  `*` 는 /me 도 매칭하나, insight 의 myGrass 가 principal null 이면 스스로 401 → 무해.
                    //  로그인 사용자는 토큰이 있어 gateway 가 aud=insight-api 내부토큰을 주입(resolveAudience).
                    "/api/v1/users/*/activity-grass",
                    // Swagger UI(aggregator) + 게이트웨이 자체 docs + 다운스트림 docs 프록시 경로
                    "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/api-docs/**"
                ).permitAll()
                .anyRequest().authenticated()
            }
            .exceptionHandling {
                // shared-error 의 enriched ProblemDetail 핸들러 — JWT 세부 사유(TOKEN_EXPIRED 등)를 FE 가 분기 가능
                it.authenticationEntryPoint(authEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .oauth2ResourceServer { rs ->
                rs.bearerTokenResolver(sseAwareBearerTokenResolver)
                rs.jwt { it.decoder(jwtDecoder) }
            }
            .addFilterAfter(headerInjectionFilter, BearerTokenAuthenticationFilter::class.java)
        return http.build()
    }
}
