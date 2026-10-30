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

    override fun call(requestJson: String): String {
        // 응답을 ByteArray 로 받아 UTF-8 로 직접 디코드한다.
        // (일부 응답이 content-type: application/octet-stream 으로 와 String 컨버터가 실패하던 문제 회피 — 본문은 항상 JSON)
        val bytes = client.post()
            .uri("/v1/messages")
            .body(requestJson)
            .retrieve()
            .body(ByteArray::class.java)
            ?: throw IllegalStateException("empty anthropic response")
        return String(bytes, Charsets.UTF_8)
    }
}
