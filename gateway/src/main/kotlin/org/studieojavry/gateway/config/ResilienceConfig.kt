package org.studieojavry.gateway.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder
import org.springframework.cloud.client.circuitbreaker.Customizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 모든 게이트웨이 라우트의 CircuitBreaker / TimeLimiter 기본 정책.
 * 라우트별 override 가 필요하면 yml 의 filter args 또는 별도 Customizer.
 *
 * 정책 요지:
 *  - 슬라이딩 윈도우 20개 호출 중 50% 실패 → OPEN
 *  - OPEN 상태 30초 → HALF_OPEN 시도
 *  - HALF_OPEN 에서 5건 시도 → 성공률 따라 CLOSED/OPEN 결정
 *  - 모든 호출에 10초 타임아웃
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
                        .timeoutDuration(Duration.ofSeconds(10))
                        .build()
                )
                .build()
        }
    }
}
