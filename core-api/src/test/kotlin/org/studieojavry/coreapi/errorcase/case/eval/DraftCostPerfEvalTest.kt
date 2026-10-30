package org.studieojavry.coreapi.errorcase.case.eval

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.studieojavry.coreapi.errorcase.case.config.LlmProperties
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.AnthropicCaller
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.LlmDraftExtractionAdapter
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.RestClientAnthropicCaller

/**
 * 실험 E — 비용·성능 균형(Haiku vs Sonnet).
 *
 * **같은 test 207 · 같은 기준 · 같은 작업**을 모델만 바꿔 돌리고, 정확도뿐 아니라 **지연(p50/p95)·토큰·추정 비용**을 함께 계측한다.
 * 어댑터는 모델군별로 구조화 전략이 다르다: Haiku=강제 tool_choice, Sonnet=auto+텍스트 폴백
 * ([LlmDraftExtractionAdapter.supportsForcedToolChoice] 가 분기 — 확장사고 모델은 강제 tool_choice 미지원).
 *
 * 실행:
 *   LLM_API_KEY=sk-ant-... \
 *     [COST_MODELS=claude-haiku-5-5,claude-sonnet-5-5] \
 *     [HAIKU_PRICE_IN=.. HAIKU_PRICE_OUT=.. SONNET_PRICE_IN=.. SONNET_PRICE_OUT=..]  # USD / 1M tokens, 입력 시에만 비용 계산 \
 *     ./gradlew :core-api:cleanTest :core-api:test --tests "*DraftCostPerfEvalTest"
 *
 * 비용은 현재 공식 단가를 env 로 넣을 때만 계산한다(단가를 코드에 박아 추정치를 지어내지 않음 — no-fabrication).
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class DraftCostPerfEvalTest {

    /** 호출당 latency(ms) 와 usage 토큰을 적재하는 계측 래퍼. 실패 호출은 토큰/지연에서 제외. */
    private class MeteredCaller(private val delegate: AnthropicCaller, private val om: ObjectMapper) : AnthropicCaller {
        val latenciesMs = mutableListOf<Long>()
        var inTokens = 0L
        var outTokens = 0L
        var okCalls = 0
        override fun call(requestJson: String): String {
            val t0 = System.nanoTime()
            val resp = delegate.call(requestJson) // 실패 시 예외 전파 → 어댑터가 잡음(계측 안 함)
            latenciesMs += (System.nanoTime() - t0) / 1_000_000
            okCalls++
            val u = om.readTree(resp).path("usage")
            inTokens += u.path("input_tokens").asLong(0)
            outTokens += u.path("output_tokens").asLong(0)
            return resp
        }
    }

    private data class ModelRun(val model: String, val report: DraftEval.Report, val meter: MeteredCaller)

    @Test
    fun `Haiku vs Sonnet 정확도·지연·토큰·비용 계측`() {
        val key = System.getenv("LLM_API_KEY")
        val models = (System.getenv("COST_MODELS") ?: "claude-haiku-5-5,claude-sonnet-5-5")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val om = ObjectMapper()
        val samples = EvalDataset.test

        val runs = models.map { model ->
            val props = LlmProperties(enabled = true, apiKey = key, model = model, fewShot = false)
            val meter = MeteredCaller(RestClientAnthropicCaller(props), om)
            val adapter = LlmDraftExtractionAdapter(props, meter, om)
            ModelRun(model, DraftEval.run(adapter, samples), meter)
        }

        fun pctl(xs: List<Long>, p: Double): Long {
            if (xs.isEmpty()) return 0
            val s = xs.sorted()
            val idx = (Math.ceil(p / 100.0 * s.size) - 1).toInt().coerceIn(0, s.size - 1)
            return s[idx]
        }
        // USD / 1M tokens — env 로 들어온 모델만 비용 계산.
        fun price(model: String, dir: String): Double? {
            val key2 = when {
                model.contains("haiku") -> "HAIKU"
                model.contains("sonnet") -> "SONNET"
                model.contains("opus") -> "OPUS"
                else -> model.uppercase().replace(Regex("[^A-Z0-9]"), "_")
            }
            return System.getenv("${key2}_PRICE_$dir")?.toDoubleOrNull()
        }
        fun costPer1k(r: ModelRun): String {
            val pin = price(r.model, "IN"); val pout = price(r.model, "OUT")
            val n = r.meter.okCalls
            if (pin == null || pout == null || n == 0) return "단가입력필요"
            val avgIn = r.meter.inTokens.toDouble() / n
            val avgOut = r.meter.outTokens.toDouble() / n
            val per1k = (avgIn * pin + avgOut * pout) / 1_000_000.0 * 1000.0
            return "$" + "%.3f".format(per1k)
        }

        fun cell(f: (ModelRun) -> String) = runs.joinToString("") { f(it).padEnd(16) }
        val report = buildString {
            appendLine()
            appendLine("=== 비용·성능 (test ${samples.size}건) — ${runs.joinToString(" vs ") { it.model }} ===")
            appendLine("  ${"지표".padEnd(22)} ${runs.joinToString("") { it.model.removePrefix("claude-").padEnd(16) }}")
            appendLine("  ${"exceptionClass".padEnd(22)} ${cell { DraftEval.pct(it.report.exAcc) }}")
            appendLine("  ${"title 키워드".padEnd(22)} ${cell { DraftEval.pct(it.report.titleRate) }}")
            appendLine("  ${"severity exact".padEnd(22)} ${cell { DraftEval.pct(it.report.sevExact) }}")
            appendLine("  ${"severity acceptable".padEnd(22)} ${cell { DraftEval.pct(it.report.sevAcceptable) }}")
            appendLine("  ${"tag precision".padEnd(22)} ${cell { DraftEval.pctN(it.report.tagPrecision) }}")
            appendLine("  ${"tag core-coverage".padEnd(22)} ${cell { DraftEval.pct(it.report.tagCoverage) }}")
            appendLine("  ${"성공 호출 수".padEnd(22)} ${cell { it.meter.okCalls.toString() }}")
            appendLine("  ${"p50 latency(ms)".padEnd(22)} ${cell { pctl(it.meter.latenciesMs, 50.0).toString() }}")
            appendLine("  ${"p95 latency(ms)".padEnd(22)} ${cell { pctl(it.meter.latenciesMs, 95.0).toString() }}")
            appendLine("  ${"평균 입력 토큰".padEnd(22)} ${cell { if (it.meter.okCalls == 0) "0" else (it.meter.inTokens / it.meter.okCalls).toString() }}")
            appendLine("  ${"평균 출력 토큰".padEnd(22)} ${cell { if (it.meter.okCalls == 0) "0" else (it.meter.outTokens / it.meter.okCalls).toString() }}")
            appendLine("  ${"1,000회 추정 비용".padEnd(22)} ${cell { costPer1k(it) }}")
            appendLine()
            appendLine("※ 비용은 *_PRICE_IN/OUT(USD/1M tokens) env 를 넣은 모델만 계산(미입력 시 '단가입력필요'). 지연은 네트워크·리전·부하에 따라 달라짐.")
        }
        println(report)
        java.io.File("build/cost-perf-report.txt").apply { parentFile.mkdirs() }.writeText(report)
    }
}
