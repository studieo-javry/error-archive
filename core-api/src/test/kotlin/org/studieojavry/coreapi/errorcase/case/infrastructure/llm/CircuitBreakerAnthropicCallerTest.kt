package org.studieojavry.coreapi.errorcase.case.infrastructure.llm

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadProvider
import java.time.Duration

/**
 * CircuitBreaker("llm") 동작 — 라이브 API 없이 결정적으로 검증.
 * 연속 실패가 임계치를 넘으면 회로가 OPEN 되어, 이후 호출은 **delegate(실제 HTTP)를 때리지 않고 즉시 실패**한다
 * (어댑터는 이 예외를 삼켜 정규식 폴백으로 떨어진다).
 */
class CircuitBreakerAnthropicCallerTest {

    private class CountingCaller(private val fail: Boolean) : AnthropicCaller {
        var calls = 0
        override fun call(requestJson: String): String {
            calls++
            if (fail) throw RuntimeException("503 upstream down")
            return """{"content":[]}"""
        }
    }

    /** 작은 창(4건)으로 빠르게 OPEN 되게 구성한 "llm" 팩토리. */
    private fun smallWindowFactory(): Resilience4JCircuitBreakerFactory {
        val factory = Resilience4JCircuitBreakerFactory(
            CircuitBreakerRegistry.ofDefaults(),
            TimeLimiterRegistry.ofDefaults(),
            Resilience4jBulkheadProvider(
                ThreadPoolBulkheadRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults(),
                Resilience4JConfigurationProperties(),
            ),
        )
        factory.configure(
            { builder ->
                builder
                    .circuitBreakerConfig(
                        CircuitBreakerConfig.custom()
                            .slidingWindowSize(4)
                            .minimumNumberOfCalls(4)
                            .failureRateThreshold(50f)
                            .waitDurationInOpenState(Duration.ofSeconds(60)) // 테스트 중 재진입 방지
                            .build(),
                    )
                    .timeLimiterConfig(
                        TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build(),
                    )
                    .build()
            },
            "llm",
        )
        return factory
    }

    @Test
    fun `연속 실패가 임계치를 넘으면 회로가 OPEN 되어 delegate 를 더는 호출하지 않는다`() {
        val delegate = CountingCaller(fail = true)
        val caller = CircuitBreakerAnthropicCaller(delegate, smallWindowFactory())

        // 창(4) 을 실패로 채운다 — 각 호출은 예외로 전파됨.
        repeat(4) { assertThrows(Exception::class.java) { caller.call("{}") } }
        val callsWhenOpen = delegate.calls
        assertEquals(4, callsWhenOpen, "OPEN 전까지는 실제 호출이 일어난다")

        // 회로 OPEN → 이후 호출은 즉시 예외, delegate 는 더 불리지 않는다.
        repeat(5) { assertThrows(Exception::class.java) { caller.call("{}") } }
        assertEquals(callsWhenOpen, delegate.calls, "OPEN 이후 delegate 호출이 늘지 않아야 함(즉시 폴백)")
    }

    @Test
    fun `정상 응답은 그대로 통과시킨다`() {
        val delegate = CountingCaller(fail = false)
        val caller = CircuitBreakerAnthropicCaller(delegate, smallWindowFactory())
        val resp = caller.call("{}")
        assertTrue(resp.contains("content"), "정상 응답 본문 반환")
        assertEquals(1, delegate.calls)
    }
}
