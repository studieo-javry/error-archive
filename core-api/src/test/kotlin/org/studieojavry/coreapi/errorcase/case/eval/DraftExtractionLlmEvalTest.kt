package org.studieojavry.coreapi.errorcase.case.eval

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.studieojavry.coreapi.errorcase.case.config.LlmProperties
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.LlmDraftExtractionAdapter
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.NoOpDraftExtractionAdapter
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.RestClientAnthropicCaller

/**
 * **정규식 vs LLM 비교 eval** — 실제 Anthropic 을 호출하므로 `LLM_API_KEY` 가 있을 때만 실행(없으면 skip).
 *
 * 실행:
 *   LLM_API_KEY=sk-ant-... ./gradlew test --tests "*DraftExtractionLlmEvalTest"
 *   (선택) LLM_MODEL=claude-haiku-5-5
 *
 * 같은 데이터셋/하네스(`DraftEval`)를 정규식(NoOp)·LLM 어댑터에 각각 돌려 지표를 나란히 출력한다.
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class DraftExtractionLlmEvalTest {

    @Test
    fun `정규식 baseline vs LLM 비교`() {
        val key = System.getenv("LLM_API_KEY")
        val model = System.getenv("LLM_MODEL") ?: "claude-haiku-5-5"
        val props = LlmProperties(enabled = true, apiKey = key, model = model)
        val llmAdapter = LlmDraftExtractionAdapter(props, RestClientAnthropicCaller(props), ObjectMapper())

        val base = DraftEval.run(NoOpDraftExtractionAdapter())
        val ai = DraftEval.run(llmAdapter)

        fun row(label: String, a: Double, b: Double) =
            "  ${label.padEnd(22)} ${DraftEval.pct(a).padEnd(8)} ${DraftEval.pct(b)}"

        println(
            buildString {
                appendLine()
                appendLine("=== 정규식(baseline) vs LLM($model) · samples=${ai.n} ===")
                appendLine("  ${"지표".padEnd(22)} ${"정규식".padEnd(8)} LLM")
                appendLine(row("exceptionClass 정확도", base.exAcc, ai.exAcc))
                appendLine(row("title 키워드 적중", base.titleRate, ai.titleRate))
                appendLine(row("severity 정확도", base.sevAcc, ai.sevAcc))
                appendLine(row("tag precision", base.tagPrecision, ai.tagPrecision))
                appendLine("--- LLM 표본별 ---")
                ai.lines.forEach { appendLine(it) }
            },
        )
    }
}
