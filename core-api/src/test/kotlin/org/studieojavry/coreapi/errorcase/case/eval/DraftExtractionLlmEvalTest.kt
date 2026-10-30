package org.studieojavry.coreapi.errorcase.case.eval

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.studieojavry.coreapi.errorcase.case.config.LlmProperties
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.LlmDraftExtractionAdapter
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.NoOpDraftExtractionAdapter
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.RestClientAnthropicCaller

/**
 * **정규식 vs LLM vs LLM+few-shot 비교 eval** — 실제 Anthropic 호출이라 `LLM_API_KEY` 있을 때만 실행(없으면 skip).
 *
 * 실행:
 *   LLM_API_KEY=sk-ant-... ./gradlew test --tests "*DraftExtractionLlmEvalTest"
 *   (선택) LLM_MODEL=claude-haiku-5-5
 *
 * **봉인 test 세트(dataset-15.jsonl)** 로 측정. 지표(Phase 0 §6): exceptionClass·title·severity exact/acceptable·
 *   tag precision/core-coverage·빈 태그율. 이 하네스는 최종 UseCase 경로(사용자 체감면)를 잰다(§6.3).
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

        val samples = EvalDataset.test
        val base = DraftEval.run(NoOpDraftExtractionAdapter(), samples)
        val ai = DraftEval.run(plainLlm, samples)
        val fs = DraftEval.run(fewShotLlm, samples)

        fun row(label: String, f: (DraftEval.Report) -> String) =
            "  ${label.padEnd(24)} ${f(base).padEnd(12)} ${f(ai).padEnd(12)} ${f(fs)}"

        val report = buildString {
            appendLine()
            appendLine("=== 정규식 vs LLM vs LLM+few-shot · model=$model · test ${ai.n}건 ===")
            appendLine("  ${"지표".padEnd(24)} ${"정규식".padEnd(12)} ${"LLM".padEnd(12)} LLM+FS")
            appendLine(row("exceptionClass 정확도") { DraftEval.pct(it.exAcc) })
            appendLine(row("title 키워드 적중") { DraftEval.pct(it.titleRate) })
            appendLine(row("severity exact") { DraftEval.pct(it.sevExact) })
            appendLine(row("severity acceptable") { DraftEval.pct(it.sevAcceptable) })
            appendLine(row("tag precision") { DraftEval.pctN(it.tagPrecision) })
            appendLine(row("tag core-coverage") { DraftEval.pct(it.tagCoverage) })
            appendLine(row("빈 태그 출력율") { DraftEval.pct(it.emptyTagRate) })
            appendLine("--- LLM(plain) 표본별 ---")
            ai.lines.forEach { appendLine(it) }
            appendLine("--- LLM+few-shot 표본별 ---")
            fs.lines.forEach { appendLine(it) }
        }
        println(report)
        // 콘솔 캡처가 환경마다 달라 리포트를 파일로도 남긴다.
        java.io.File("build/eval-report.txt").apply { parentFile.mkdirs() }.writeText(report)
    }
}
