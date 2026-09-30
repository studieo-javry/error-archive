package org.studieojavry.coreapi.shared.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder
import org.springframework.cloud.client.circuitbreaker.Customizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * core-api 의 외부 호출용 CircuitBreaker 기본 정책.
 *
 * - 슬라이딩 윈도우 20건 중 50% 실패 → OPEN
 * - OPEN 30초 → HALF_OPEN
 * - HALF_OPEN 5건 시도
 * - 모든 호출 5초 timeout (게이트웨이의 10s 보다 짧게 — caller 가 먼저 끊어야 thread 회수가 빠름)
 *
 * ⚠️ TimeLimiter 가 설정되면 Spring Cloud CircuitBreaker 는 supplier 를 별도 ExecutorService
 *    스레드에서 실행한다. 그 worker 스레드에는 요청 스레드의 SecurityContext(thread-local)가
 *    전파되지 않으므로, iam-api 호출용 RestClient interceptor 의 currentUser() 가 사용자를
 *    못 읽어 internal token 을 못 붙인다 → iam-api 가 401.
 *    → executor 를 DelegatingSecurityContextExecutorService 로 감싸 제출 스레드의
 *      SecurityContext 를 worker 스레드로 전파한다.
 */
@Configuration
class ResilienceConfig {

    /**
     * CircuitBreaker(TimeLimiter)용 스레드 풀 — **bounded(bulkhead)**.
     *
     * 기존 `newCachedThreadPool()` 은 무제한이라 iam-api 지연 시 대기 스레드가 무한정 증가한다
     * (부하 테스트에서 48→143 관측 — 정체는 future 를 기다리는 **Tomcat 워커 스레드**였음).
     * **SynchronousQueue + 상한 풀 + AbortPolicy** 로 동시 외부호출을 max(16)개로 제한하고 초과분은
     * **즉시 reject** → CircuitBreaker 가 실패로 집계 → **빠른 fallback**. 큐가 없으므로 Tomcat 워커가
     * 큐 뒤에서 5초씩 블록되지 않고 곧바로 풀려, 대기 스레드 총량이 ~16 로 묶인다. CB OPEN 도 앞당김.
     */
    @Bean(destroyMethod = "shutdown")
    fun circuitBreakerExecutor(): ExecutorService = ThreadPoolExecutor(
        0, 16, 30L, TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Bean
    fun defaultCircuitBreakerCustomizer(
        circuitBreakerExecutor: ExecutorService,
    ): Customizer<Resilience4JCircuitBreakerFactory> = Customizer { factory ->
        factory.configureExecutorService(DelegatingSecurityContextExecutorService(circuitBreakerExecutor))
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
