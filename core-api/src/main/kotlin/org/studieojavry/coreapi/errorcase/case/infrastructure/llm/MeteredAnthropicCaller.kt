package org.studieojavry.coreapi.errorcase.case.infrastructure.llm

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.config.LlmProperties

/**
 * 실제 Anthropic HTTP 호출을 **Micrometer 로 계측**하는 데코레이터(가장 안쪽, 실 호출 직전).
 *
 * CircuitBreaker 보다 **안쪽**에 둬서, 회로가 OPEN 되어 호출이 차단된 경우는 **계측하지 않는다**
 * (= 실제 발생한 API 호출·토큰·비용만 집계). 호출 성공/실패, 입력/출력 토큰, 추정 비용, 지연을 노출한다.
 *
 * 노출 메트릭(Prometheus):
 *  - `llm_draft_api_calls_total{outcome="success|failure"}`  호출 수
 *  - `llm_draft_api_latency_seconds{...}`                    호출 지연(Timer)
 *  - `llm_draft_tokens_total{type="input|output"}`          토큰 누적
 *  - `llm_draft_estimated_cost_usd_total`                    추정 비용 누적(단가 미설정이면 0)
 */
@Component
@ConditionalOnProperty(prefix = "llm", name = ["enabled"], havingValue = "true")
class MeteredAnthropicCaller(
    @Qualifier("restClientAnthropicCaller") private val delegate: AnthropicCaller,
    private val meterRegistry: MeterRegistry,
    private val props: LlmProperties,
    private val om: ObjectMapper = ObjectMapper(),
) : AnthropicCaller {

    private val latency: Timer = Timer.builder("llm.draft.api.latency")
        .description("Anthropic draft 호출 지연")
        .publishPercentiles(0.5, 0.95)
        .register(meterRegistry)

    override fun call(requestJson: String): String {
        val sample = Timer.start(meterRegistry)
        try {
            val resp = delegate.call(requestJson)
            sample.stop(latency)
            meterRegistry.counter("llm.draft.api.calls", "outcome", "success").increment()
            recordUsage(resp)
            return resp
        } catch (e: Exception) {
            sample.stop(latency)
            meterRegistry.counter("llm.draft.api.calls", "outcome", "failure").increment()
            throw e
        }
    }

    /** 응답 usage 에서 입력/출력 토큰을 읽어 토큰·추정비용 누적. 파싱 실패는 계측만 건너뛴다(호출엔 영향 없음). */
    private fun recordUsage(resp: String) {
        runCatching {
            val usage = om.readTree(resp).path("usage")
            val inTok = usage.path("input_tokens").asLong(0)
            val outTok = usage.path("output_tokens").asLong(0)
            if (inTok > 0) meterRegistry.counter("llm.draft.tokens", "type", "input").increment(inTok.toDouble())
            if (outTok > 0) meterRegistry.counter("llm.draft.tokens", "type", "output").increment(outTok.toDouble())
            val cost = inTok / 1_000_000.0 * props.priceInputPerMtok + outTok / 1_000_000.0 * props.priceOutputPerMtok
            if (cost > 0) meterRegistry.counter("llm.draft.estimated.cost.usd").increment(cost)
        }
    }
}
