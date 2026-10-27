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
                auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // prod 는 base-path=/internal/actuator → health/liveness/readiness 프로브가 이 경로.
                    // k8s kubelet 프로브(토큰 없음)가 200 을 받으려면 인증 없이 열려야 한다.
                    .requestMatchers("/internal/actuator/health", "/internal/actuator/health/**").permitAll()
                    // Prometheus 메트릭 스크레이프 — /internal/** 은 gateway 가 외부 라우팅 안 함 → 클러스터 내부만 도달.
                    .requestMatchers("/actuator/prometheus", "/internal/actuator/prometheus").permitAll()
                    .requestMatchers("/error").permitAll()
                    // OpenAPI 문서 / Swagger UI — 인증 없이 열람. (실제 API 호출은 X-Internal-Auth 필요)
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // 로컬 테스트 페이지 — 정적 리소스(같은 origin). API 호출은 여전히 X-Test-User-Id 필요.
                    .requestMatchers("/comment-tester.html", "/comment-tester/**").permitAll()
                    // 게스트("로그인 없이 둘러보기")는 첨부 업로드 금지 — S3 비용/남용 방어.
                    // (멘션 초대/공개 승격은 워크스페이스 ADMIN 전용이라 게스트 WRITE 역할만으로 이미 차단됨.)
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/error-attachments")
                    .access(denyGuests())
                    // 게스트는 AI 초안 제안 금지 — 외부 LLM 호출 비용/남용 방어(로그인 사용자 전용).
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/error-cases/draft-suggestion")
                    .access(denyGuests())
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

    /**
     * 인증은 됐지만 `ROLE_GUEST` authority 를 가진 요청은 거부(그 외 인증 사용자는 허용).
     * 일반 사용자는 roles claim 이 비어 ROLE_* authority 가 없으므로 통과하고, 게스트 토큰만 걸린다.
     */
    private fun denyGuests(): org.springframework.security.authorization.AuthorizationManager<org.springframework.security.web.access.intercept.RequestAuthorizationContext> =
        org.springframework.security.authorization.AuthorizationManager { authentication, _ ->
            val auth = authentication.get()
            val allowed = auth != null && auth.isAuthenticated &&
                auth.authorities.none { it.authority == "ROLE_GUEST" }
            org.springframework.security.authorization.AuthorizationDecision(allowed)
        }
}
