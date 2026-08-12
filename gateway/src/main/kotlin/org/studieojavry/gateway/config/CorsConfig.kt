package org.studieojavry.gateway.config

import jakarta.validation.constraints.NotEmpty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Validated
@ConfigurationProperties(prefix = "gateway.cors")
data class CorsProperties(
    @field:NotEmpty
    val allowedOrigins: List<String>,
    val allowedMethods: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
    val allowedHeaders: List<String> = listOf("*"),
    val allowCredentials: Boolean = true,
    val maxAgeSeconds: Long = 3600
)

@Configuration
class CorsConfig {

    @Bean
    fun corsConfigurationSource(properties: CorsProperties): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = properties.allowedOrigins
            allowedMethods = properties.allowedMethods
            allowedHeaders = properties.allowedHeaders
            allowCredentials = properties.allowCredentials
            maxAge = properties.maxAgeSeconds
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }
}
