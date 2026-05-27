package org.studieojavry.iamapi.shared.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder
import org.springframework.cloud.client.circuitbreaker.Customizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * iam-api 의 외부 호출용 CircuitBreaker 기본 정책.
 *
 * 주 용도: GitHub OAuth 호출 (token 교환, /user, /user/emails) 보호.
 *
 * - 슬라이딩 윈도우 20건 중 50% 실패 → OPEN
 * - OPEN 30초 → HALF_OPEN
 * - HALF_OPEN 5건 시도
 * - 모든 호출 5초 timeout
 */
@Configuration
class ResilienceConfig {

    @Bean
    fun defaultCircuitBreakerCustomizer(): Customizer<Resilience4JCircuitBreakerFactory> = Customizer { factory ->
        factory.configureDefault { id ->
            Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(
                    CircuitBreakerConfig.custom()
                        .slidingWindowSize(20)
                        .failureRateThreshold(50f)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(5)
                        .minimumNumberOfCalls(10)
                        .build()
                )
                .timeLimiterConfig(
                    TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(5))
                        .build()
                )
                .build()
        }
    }
}
