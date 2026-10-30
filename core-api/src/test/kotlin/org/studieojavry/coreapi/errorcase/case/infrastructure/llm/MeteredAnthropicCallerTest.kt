package org.studieojavry.coreapi.errorcase.case.infrastructure.llm

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.case.config.LlmProperties

/**
 * 호출량·토큰·추정비용 계측 검증 — SimpleMeterRegistry 로 결정적.
 */
class MeteredAnthropicCallerTest {

    private val om = ObjectMapper()

    private class FakeCaller(private val resp: String?, private val ex: RuntimeException? = null) : AnthropicCaller {
        override fun call(requestJson: String): String = ex?.let { throw it } ?: resp!!
    }

    private fun props(pin: Double = 0.0, pout: Double = 0.0) =
        LlmProperties(enabled = true, priceInputPerMtok = pin, priceOutputPerMtok = pout)

    @Test
    fun `성공 호출은 calls success + 토큰 + 비용을 집계한다`() {
        val reg = SimpleMeterRegistry()
        val resp = """{"content":[],"usage":{"input_tokens":1000,"output_tokens":200}}"""
        // 단가: 입력 $1/Mtok, 출력 $5/Mtok → 1000/1e6*1 + 200/1e6*5 = 0.001 + 0.001 = 0.002
        val caller = MeteredAnthropicCaller(FakeCaller(resp), reg, props(pin = 1.0, pout = 5.0), om)

        caller.call("{}")

        assertEquals(1.0, reg.counter("llm.draft.api.calls", "outcome", "success").count())
        assertEquals(1000.0, reg.counter("llm.draft.tokens", "type", "input").count())
        assertEquals(200.0, reg.counter("llm.draft.tokens", "type", "output").count())
        assertEquals(0.002, reg.counter("llm.draft.estimated.cost.usd").count(), 1e-9)
        assertEquals(1L, reg.timer("llm.draft.api.latency").count())
    }

    @Test
    fun `단가 미설정이면 토큰은 집계하되 비용은 0`() {
        val reg = SimpleMeterRegistry()
        val resp = """{"usage":{"input_tokens":500,"output_tokens":100}}"""
        MeteredAnthropicCaller(FakeCaller(resp), reg, props(), om).call("{}")

        assertEquals(500.0, reg.counter("llm.draft.tokens", "type", "input").count())
        assertEquals(0.0, reg.counter("llm.draft.estimated.cost.usd").count())
    }

    @Test
    fun `실패 호출은 calls failure 로 집계하고 예외를 전파한다`() {
        val reg = SimpleMeterRegistry()
        val caller = MeteredAnthropicCaller(FakeCaller(null, RuntimeException("503")), reg, props(), om)

        assertThrows(RuntimeException::class.java) { caller.call("{}") }
        assertEquals(1.0, reg.counter("llm.draft.api.calls", "outcome", "failure").count())
        assertEquals(0.0, reg.counter("llm.draft.api.calls", "outcome", "success").count())
    }
}
