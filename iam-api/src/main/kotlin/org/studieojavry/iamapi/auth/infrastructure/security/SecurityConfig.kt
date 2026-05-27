package org.studieojavry.iamapi.auth.infrastructure.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter

@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        bearerTokenResolver: BearerTokenResolver,
    ): SecurityFilterChain {
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
                auth.requestMatchers(
                    // OAuth 시작 / 콜백 — 로그인 *전* 이라 인증 없이 호출돼야 함
                    "/api/v1/auth/oauth/**",
                    // refresh / logout — refresh_token 쿠키만 가지고 호출. access 가 만료된 상태에서도 가능해야 하므로 permitAll.
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/actuator/health",
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
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
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
