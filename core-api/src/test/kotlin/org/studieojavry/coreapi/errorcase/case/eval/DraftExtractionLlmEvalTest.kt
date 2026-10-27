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
    fun `정규식 baseline vs LLM vs LLM+few-shot 비교`() {
        val key = System.getenv("LLM_API_KEY")
        val model = System.getenv("LLM_MODEL") ?: "claude-haiku-5-5"
        val om = ObjectMapper()

        val plainProps = LlmProperties(enabled = true, apiKey = key, model = model, fewShot = false)
        val fewShotProps = plainProps.copy(fewShot = true)
        val plainLlm = LlmDraftExtractionAdapter(plainProps, RestClientAnthropicCaller(plainProps), om)
        val fewShotLlm = LlmDraftExtractionAdapter(fewShotProps, RestClientAnthropicCaller(fewShotProps), om)

        val base = DraftEval.run(NoOpDraftExtractionAdapter())
        val ai = DraftEval.run(plainLlm)
        val fs = DraftEval.run(fewShotLlm)

        fun row(label: String, a: Double, b: Double, c: Double) =
            "  ${label.padEnd(22)} ${DraftEval.pct(a).padEnd(8)} ${DraftEval.pct(b).padEnd(8)} ${DraftEval.pct(c)}"

        val report = buildString {
            appendLine()
            appendLine("=== 정규식 vs LLM vs LLM+few-shot · model=$model · samples=${ai.n} ===")
            appendLine("  ${"지표".padEnd(22)} ${"정규식".padEnd(8)} ${"LLM".padEnd(8)} LLM+FS")
            appendLine(row("exceptionClass 정확도", base.exAcc, ai.exAcc, fs.exAcc))
            appendLine(row("title 키워드 적중", base.titleRate, ai.titleRate, fs.titleRate))
            appendLine(row("severity 정확도", base.sevAcc, ai.sevAcc, fs.sevAcc))
            appendLine(row("tag precision", base.tagPrecision, ai.tagPrecision, fs.tagPrecision))
            appendLine("--- LLM(plain) 표본별 ---")
            ai.lines.forEach { appendLine(it) }
            appendLine("--- LLM+few-shot 표본별 ---")
            fs.lines.forEach { appendLine(it) }
        }
        println(report)
        // 콘솔 캡처가 환경마다 달라, 리포트를 파일로도 남긴다(build/eval-report.txt).
        java.io.File("build/eval-report.txt").apply { parentFile.mkdirs() }.writeText(report)
    }
}
