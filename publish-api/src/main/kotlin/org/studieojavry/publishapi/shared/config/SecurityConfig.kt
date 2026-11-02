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
                    // 검색엔진 discovery — sitemap/robots 는 크롤러가 로그인 없이 읽어야 함(게이트웨이도 permitAll).
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/sitemap.xml", "/robots.txt").permitAll()
                    // 공개 Explore 목록(PUBLIC+LIVE 쇼케이스) — 로그아웃도 접근(랜딩/SEO/데모). GET 만 개방.
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/publishments/public").permitAll()
                    // 게스트("로그인 없이 둘러보기")는 발행 금지 — 외부(공개 웹) 노출 방어. 발행물 생성 차단만으로
                    // 이후 publish/unpublish 대상 자체가 없어진다(게스트는 발행물을 만들 수 없음).
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/publishments")
                    .access(denyGuests())
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

    /**
     * 인증은 됐지만 `ROLE_GUEST` authority 를 가진 요청은 거부(그 외 인증 사용자는 허용).
     * 일반 사용자는 roles claim 이 비어 ROLE_* authority 가 없어 통과하고, 게스트 토큰만 걸린다.
     */
    private fun denyGuests(): org.springframework.security.authorization.AuthorizationManager<org.springframework.security.web.access.intercept.RequestAuthorizationContext> =
        org.springframework.security.authorization.AuthorizationManager { authentication, _ ->
            val auth = authentication.get()
            val allowed = auth != null && auth.isAuthenticated &&
                auth.authorities.none { it.authority == "ROLE_GUEST" }
            org.springframework.security.authorization.AuthorizationDecision(allowed)
        }
}
