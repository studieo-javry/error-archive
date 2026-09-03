package org.studieojavry.iamapi.auth.infrastructure.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.studieojavry.sharederror.security.ProblemDetailAccessDeniedHandler
import org.studieojavry.sharederror.security.ProblemDetailAuthenticationEntryPoint

@Configuration
class SecurityConfig {

    /**
     * CORS — local 한정 모든 origin 허용 (playground HTML 이 `file://` 또는 임의 localhost 에서 호출).
     * dev/stg/prod 는 빈 config = 모두 거부 → 게이트웨이 경유 강제.
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
                allowCredentials = false   // pattern("*") + credentials=true 는 충돌, 우린 헤더만
            }
        }
        // *dev fixture (`/__dev/**`) 만* CORS 응답. production endpoint 는 gateway 책임 —
        // 둘 다 응답하면 Allow-Origin 헤더 중복으로 browser 가 차단.
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/__dev/**", config) }
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        bearerTokenResolver: BearerTokenResolver,
        authEntryPoint: ProblemDetailAuthenticationEntryPoint,
        accessDeniedHandler: ProblemDetailAccessDeniedHandler,
        environment: Environment,
    ): SecurityFilterChain {
        // local profile 한정 — /actuator/** 전체 permitAll.
        // 검증/디버깅 편의 (Micrometer Gauge 즉시 호출). dev/stg/prod 는 internal-auth 보호 유지.
        val isLocal = environment.activeProfiles.contains("local") ||
            (environment.activeProfiles.isEmpty() && environment.defaultProfiles.contains("local"))
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .headers { headers ->
                headers
                    .contentTypeOptions(Customizer.withDefaults())
                    .frameOptions { it.deny() }
                    .referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                    .xssProtection { it.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK) }
                    .httpStrictTransportSecurity { it.includeSubDomains(true).maxAgeInSeconds(31_536_000) }
            }
            .authorizeHttpRequests { auth ->
                // local profile 만 /actuator/** + /__dev/** 전체 permitAll.
                // dev fixture (OAuth 우회 access token 발급 등) 호출에 인증 우회.
                if (isLocal) {
                    auth.requestMatchers("/actuator/**").permitAll()
                    auth.requestMatchers("/__dev/**").permitAll()
                }
                auth.requestMatchers(
                    // OAuth 시작 / 콜백 — 로그인 *전* 이라 인증 없이 호출돼야 함
                    "/api/v1/auth/oauth/**",
                    // refresh / logout — refresh_token 쿠키만 가지고 호출. access 가 만료된 상태에서도 가능해야 하므로 permitAll.
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    // health probe — 기본 경로 + prod 의 base-path(/internal/actuator) 하위,
                    // readiness/liveness 그룹(/health/**) 까지 permit (k8s probe 401 방지).
                    "/actuator/health", "/actuator/health/**",
                    "/internal/actuator/health", "/internal/actuator/health/**",
                    // Prometheus 메트릭 스크레이프 — /internal/** 은 gateway 가 외부로 라우팅하지 않아
                    // 클러스터 내부(관측 alloy)에서만 도달. 메트릭엔 비밀값 없음.
                    "/actuator/prometheus", "/internal/actuator/prometheus",
                    // 정적 아바타 이미지 — 공개 read. 업로드/삭제는 별도 endpoint 라 인증 필요.
                    "/avatars/**",
                    // OpenAPI 문서 / Swagger UI — 인증 없이 열람 (실 API 호출은 Authorization 필요)
                    "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                    // 에러 dispatcher — NoHandler 등 정적 미스가 /error 로 forward 될 때
                    // anyRequest().authenticated() 에 잡혀 401 이 떨어지는 것 방지.
                    "/error"
                ).permitAll()
                    // 초대 토큰 미리보기 — 비로그인 사용자가 토큰 유효성 확인용 (Accept 화면 진입 전)
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/invitations/preview").permitAll()
                    // 공개 사회 그래프 조회 (GET 만). PUT/DELETE 는 인증 필수.
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/users/*/follow-status").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/users/*/followers").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/users/*/following").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                // shared-error 의 enriched ProblemDetail 핸들러 — 401 의 세부 사유 매핑 + 403 의 일관 응답
                it.authenticationEntryPoint(authEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .oauth2ResourceServer { rs ->
                rs.bearerTokenResolver(bearerTokenResolver)
                rs.jwt { jwt ->
                    jwt.decoder(jwtDecoder)
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                }
            }
        return http.build()
    }

    private fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val authoritiesConverter = JwtGrantedAuthoritiesConverter().apply {
            setAuthorityPrefix("ROLE_")
            setAuthoritiesClaimName("roles")
        }
        return JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(authoritiesConverter)
            setPrincipalClaimName("sub")
        }
    }
}
