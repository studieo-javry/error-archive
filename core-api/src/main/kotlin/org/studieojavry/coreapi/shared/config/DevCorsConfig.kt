package org.studieojavry.coreapi.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * **로컬 테스트 전용** — playground (`home.html` 등) 가 브라우저에서 직접 core-api 호출 가능하게.
 * file:// 또는 다른 localhost port 에서 fetch 호출 시 CORS 통과 필요.
 *
 * 운영(dev/stg/prod) 에서는 게이트웨이가 처리 → 본 bean 미등록.
 */
@Configuration
@Profile("local")
class DevCorsConfig {

    @Bean
    fun devCorsConfigurationSource(): UrlBasedCorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOriginPatterns = listOf("*")
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            exposedHeaders = listOf("X-Trace-Id")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }
}
