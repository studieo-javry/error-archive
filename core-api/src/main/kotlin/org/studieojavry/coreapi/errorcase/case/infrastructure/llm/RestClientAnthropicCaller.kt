package org.studieojavry.coreapi.errorcase.case.infrastructure.llm

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.studieojavry.coreapi.errorcase.case.config.LlmProperties
import java.time.Duration

/**
 * 실제 Anthropic 호출. `llm.enabled=true` 일 때만 빈 등록.
 * (circuit breaker 는 기존 resilience4j 설정에 "llm" 추가 권장 — 여기선 타임아웃 + 어댑터의 try/catch 폴백으로 최소 방어)
 */
@Component
@ConditionalOnProperty(prefix = "llm", name = ["enabled"], havingValue = "true")
class RestClientAnthropicCaller(props: LlmProperties) : AnthropicCaller {

    private val client: RestClient = RestClient.builder()
        .baseUrl(props.baseUrl)
        .defaultHeader("x-api-key", props.apiKey)
        .defaultHeader("anthropic-version", props.anthropicVersion)
        .defaultHeader("content-type", "application/json")
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(props.timeoutMs))
                setReadTimeout(Duration.ofMillis(props.timeoutMs))
            },
        )
        .build()

    override fun call(requestJson: String): String =
        client.post()
            .uri("/v1/messages")
            .body(requestJson)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("empty anthropic response")
}
