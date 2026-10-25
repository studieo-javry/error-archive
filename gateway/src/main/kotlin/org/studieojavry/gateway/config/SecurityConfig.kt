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
                auth
                    // `/users/me/activity-grass` 는 본인 식별이 필요 → 인증 필수.
                    //  아래 공개 와일드카드(`/users/*/activity-grass`)보다 **먼저** 매칭해야 한다.
                    //  이유: permitAll 이면 gateway 가 이 경로를 "인증 불필요"로 보아, 토큰이 없어도 그대로
                    //  통과시켜 downstream 으로 보낸다 → insight 는 principal 이 없어 401 → (서킷 오염).
                    //  authenticated 로 두면 미인증은 gateway 가 즉시 401(insight 도달 X, 서킷 무관),
                    //  인증 요청은 JWT 검증 후 aud=insight-api 내부토큰이 주입되어 insight 가 me 를 해석한다.
                    .requestMatchers("/api/v1/users/me/activity-grass").authenticated()
                    .requestMatchers(
                    "/api/v1/auth/oauth/**",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    // 게스트("로그인 없이 둘러보기") — 무인증 토큰 발급. RouteConfig 에서 per-IP rate limit 적용.
                    "/api/v1/auth/guest",
                    // health probe — local(/actuator) + prod(base-path=/internal/actuator) 둘 다.
                    "/actuator/health", "/actuator/health/**",
                    "/internal/actuator/health", "/internal/actuator/health/**",
                    "/__fallback/**",
                    // 정적 아바타 이미지 — 공개 read (이미지 fetch 에 Authorization 안 가도록)
                    "/avatars/**",
                    // 공개 publish 페이지 + 공개 export (인증 X — 누구나 열람/다운로드)
                    "/p/**", "/api/v1/publishments/by-slug/**",
                    // 검색엔진 discovery — sitemap/robots 는 크롤러가 로그인 없이 읽어야 함(공개).
                    "/sitemap.xml", "/robots.txt",
                    // 공개 Explore 목록(쇼케이스) — 로그아웃도 조회(랜딩/SEO/데모). publish-api 는 GET 만 permitAll,
                    //  RouteConfig 의 publish-public-list 가 per-IP rate limit 적용. (게이트웨이 JWT 필터 skip 필수)
                    "/api/v1/publishments/public",
                    // I3: 공개 프로필 잔디 — 잔디 정책 v0.2 "전부 공개". 로그아웃 상태에서도 열람 가능해야 함.
                    //  숫자 userId 변형만 공개(타인 잔디 열람). `me` 는 위에서 authenticated 로 카브아웃했다.
                    "/api/v1/users/*/activity-grass",
                    // 초대 미리보기 — 토큰만으로 "누가·어떤 워크스페이스로 초대했는지" 로그인/가입 전 열람.
                    //  핸들러는 principal 없이 token 만 받는 익명 설계(수락 /accept 은 인증 필요). preview 만
                    //  정확히 공개하고, 워크스페이스 스코프 초대 CRUD(ADMIN)·/accept 는 authenticated 로 유지.
                    "/api/v1/invitations/preview",
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
