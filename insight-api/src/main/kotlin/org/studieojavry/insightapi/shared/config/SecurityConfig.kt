package org.studieojavry.insightapi.shared.config

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
 * insight-api 인증 — gateway 의 internal JWT(aud=insight-api) 검증.
 *
 * principal = userId (Long) — 컨트롤러가 `@AuthenticationPrincipal` 로 주입받음.
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
        // local profile 한정 — /actuator/** + 잔디 API 전체 permitAll.
        // 목적: (1) playground (activity-grass.html) 가 토큰 없이 직접 호출,
        //       (2) Micrometer Gauge 즉시 호출 가능.
        // dev/stg/prod 는 기존 룰 (health 만 + internal-auth) 유지.
        val isLocal = environment.activeProfiles.contains("local") ||
            (environment.activeProfiles.isEmpty() && environment.defaultProfiles.contains("local"))

        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                if (isLocal) {
                    auth.requestMatchers("/actuator/**").permitAll()
                }
                auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // prod 는 base-path=/internal/actuator → 프로브가 이 경로. 인증 없이 열려야 kubelet 200.
                    .requestMatchers("/internal/actuator/health", "/internal/actuator/health/**").permitAll()
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // I3: 공개 프로필 잔디 — 잔디 정책 v0.2 "전부 공개". 모든 프로파일에서 익명 열람 허용.
                    //  게이트웨이가 익명(토큰 없음) 요청을 그대로 전달 → publicGrass 가 path 의 userId 로 조회.
                    //  `*` 가 /me 도 매칭하나 myGrass 는 principal 없으면 스스로 401 → 데이터 유출 없음.
                    .requestMatchers("/api/v1/users/*/activity-grass").permitAll()
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
     * CORS — local 한정으로 모든 origin 허용 (playground HTML 이 `file://` 또는 `http://localhost:*`
     * 에서 호출하는 케이스 cover). dev/stg/prod 는 빈 origin = 모두 거부 → 게이트웨이 거치도록 강제.
     */
    @Bean
    fun corsConfigurationSource(environment: Environment): CorsConfigurationSource {
        val isLocal = environment.activeProfiles.contains("local") ||
            (environment.activeProfiles.isEmpty() && environment.defaultProfiles.contains("local"))
        val config = CorsConfiguration().apply {
            if (isLocal) {
                addAllowedOriginPattern("*")        // file://, http://localhost:*, http://127.0.0.1:* 다 허용
                addAllowedMethod("*")
                addAllowedHeader("*")
                allowCredentials = false             // pattern("*") 와 credentials=true 는 충돌, 우린 토큰 헤더만 쓰니 false OK
            }
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }
}
