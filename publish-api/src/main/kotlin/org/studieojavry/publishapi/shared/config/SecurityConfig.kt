package org.studieojavry.publishapi.shared.config

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
 * publish-api 인증
 *  - 공개 페이지 (`/p/{slug}`, `/api/v1/publishments/by-slug/{slug}`, export public 조회) 는 인증 X
 *  - 내 publish 관리 (create/update/delete/list) 는 internal JWT 필요 (aud=publish-api)
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        internalTokenFilter: InternalTokenAuthenticationFilter,
        devHeaderAuthFilter: org.springframework.beans.factory.ObjectProvider<DevHeaderAuthFilter>,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // prod 는 base-path=/internal/actuator → 프로브가 이 경로. 인증 없이 열려야 kubelet 200.
                    .requestMatchers("/internal/actuator/health", "/internal/actuator/health/**").permitAll()
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // 공개 페이지 + 공개 export + sitemap — 인증 불필요
                    .requestMatchers("/p/**").permitAll()
                    .requestMatchers("/sitemap.xml").permitAll()
                    .requestMatchers("/api/v1/publishments/by-slug/**").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter::class.java)
        devHeaderAuthFilter.ifAvailable?.let {
            http.addFilterBefore(it, UsernamePasswordAuthenticationFilter::class.java)
        }
        return http.build()
    }
}
