package org.studieojavry.coreapi.errorcase.case.infrastructure.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * Anthropic 호출을 **CircuitBreaker("llm")** 로 감싸는 데코레이터.
 *
 * 왜 데코레이터인가: 실제 HTTP 호출([RestClientAnthropicCaller])은 그대로 두고 **차단만 덧씌워**,
 * 어댑터([LlmDraftExtractionAdapter])와 그 단위 테스트(가짜 caller 주입)는 **전혀 바뀌지 않는다**.
 * 어댑터는 `AnthropicCaller`(인터페이스)를 주입받으므로 `@Primary` 인 이 빈이 실제 호출 경로로 선택된다.
 *
 * 동작: Anthropic 이 연속 실패/지연하면 회로가 **OPEN** → 이후 호출은 LLM 을 때리지 않고 **즉시 예외** →
 * 어댑터가 예외를 삼켜 **정규식 폴백(degraded)** 으로 떨어진다. 쿨다운 뒤 HALF_OPEN 으로 자동 탐침·복구.
 * (정책: [org.studieojavry.coreapi.shared.config.ResilienceConfig] 의 "llm" 인스턴스 — LLM 은 느려서
 *  기본 5s 보다 긴 TimeLimiter 를 쓴다.)
 *
 * `llm.enabled=true` 일 때만 등록 — 실제 호출 경로가 활성일 때만 의미가 있다.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "llm", name = ["enabled"], havingValue = "true")
class CircuitBreakerAnthropicCaller(
    // 계측 데코레이터(그 안에 실 HTTP caller)를 감싼다 → CB 가 최외곽. OPEN 으로 차단되면 계측도 안 탄다
    // (= 실제 발생한 호출만 메트릭에 집계). 테스트는 가짜 caller 주입 가능.
    @Qualifier("meteredAnthropicCaller") private val delegate: AnthropicCaller,
    cbFactory: CircuitBreakerFactory<*, *>,
) : AnthropicCaller {

    private val log = KotlinLogging.logger {}
    private val cb = cbFactory.create("llm")

    override fun call(requestJson: String): String = cb.run(
        { delegate.call(requestJson) },
        // 회로 OPEN(CallNotPermittedException) 또는 호출 실패 → 원인 예외를 그대로 전파.
        // 어댑터가 모든 예외를 null 로 흡수하므로 자연히 정규식 폴백으로 이어진다.
        { t ->
            val cause = t ?: IllegalStateException("llm call failed")
            log.warn(cause) { "llm circuit open/failed → 정규식 폴백: ${cause.message}" }
            throw cause
        },
    )
}
