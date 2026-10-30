package org.studieojavry.coreapi.errorcase.case.eval

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.studieojavry.coreapi.errorcase.case.application.port.DraftExtractionPort
import org.studieojavry.coreapi.errorcase.case.config.LlmProperties
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.LlmDraftExtractionAdapter
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.RestClientAnthropicCaller

/**
 * 실험 B — LLM-as-a-Judge (제목·요약의 의미적 적절성).
 *
 * 흐름: generator(Haiku)로 각 표본의 제목·요약 생성 → **judge(Sonnet, 생성≠평가)** 가 rubric 으로 채점.
 * rubric: 근거 일치 / 증상 구체성 / 근거 없는 단정(no-fabrication) / 가독성 + 요약 Faithfulness.
 * 편향 완화: judge≠generator, 짧은 근거 + pass/fail 병행. **judge 점수는 사람 검증 전까지 '정답' 아님**(아래 주의).
 *
 * 실행:
 *   LLM_API_KEY=sk-... [JUDGE_MODEL=claude-sonnet-5-5] [JUDGE_N=40] \
 *     ./gradlew :core-api:cleanTest :core-api:test --tests "*DraftJudgeEvalTest"
 *
 * ※ 먼저 build/judge-report.txt 의 샘플별 결과 중 30~50건을 사람이 독립 채점해 judge-사람 일치도를 확인한 뒤
 *   자동 평가 범위를 넓히는 것을 권장(요구사항).
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class DraftJudgeEvalTest {

    @Test
    fun `제목·요약 품질 LLM-as-judge 채점`() {
        val key = System.getenv("LLM_API_KEY")
        val genModel = System.getenv("LLM_MODEL") ?: "claude-haiku-5-5"
        val judgeModel = System.getenv("JUDGE_MODEL") ?: "claude-sonnet-5-5"
        val n = (System.getenv("JUDGE_N") ?: "40").toInt()
        val om = ObjectMapper()

        val genProps = LlmProperties(enabled = true, apiKey = key, model = genModel, fewShot = false)
        val generator = LlmDraftExtractionAdapter(genProps, RestClientAnthropicCaller(genProps), om)
        val judgeProps = genProps.copy(model = judgeModel)
        val judgeCaller = RestClientAnthropicCaller(judgeProps)

        val samples = EvalDataset.test.take(n)

        var gnd = 0.0; var spec = 0.0; var read = 0.0; var scored = 0
        var noFabPass = 0; var faithPass = 0; var faithTot = 0
        val lines = mutableListOf<String>()
        // 사람 검증 시트를 재현 가능하게: id·제목·요약·judge점수를 기계가 읽는 JSONL 로도 남긴다.
        val records = mutableListOf<Map<String, Any?>>()

        for (s in samples) {
            val out: DraftExtractionPort.Output? = runCatching {
                generator.extract(DraftExtractionPort.Input(s.paste, null, emptyList()))
            }.getOrNull()
            val title = out?.title
            val summary = out?.summary
            if (title == null) { lines += "  [skip] ${s.id} (제목 생성 실패)"; continue }

            val verdict = try {
                judge(judgeCaller, judgeModel, om, s.paste, title, summary)
            } catch (e: Exception) {
                lines += "  [skip] ${s.id} (judge 실패: ${e.message?.replace("\n", " ")?.take(160)})"; null
            }
            if (verdict == null) continue

            scored++
            gnd += verdict.grounding; spec += verdict.specificity; read += verdict.readability
            if (verdict.noFabrication == "pass") noFabPass++
            if (summary != null) { faithTot++; if (verdict.summaryFaithful == "pass") faithPass++ }
            lines += "  ${s.id.padEnd(30)} g=${verdict.grounding} s=${verdict.specificity} r=${verdict.readability} " +
                "noFab=${verdict.noFabrication} faith=${verdict.summaryFaithful}  title=\"$title\""
            records += mapOf(
                "id" to s.id, "paste" to s.paste, "title" to title, "summary" to summary,
                "grounding" to verdict.grounding, "specificity" to verdict.specificity, "readability" to verdict.readability,
                "no_fabrication" to verdict.noFabrication, "summary_faithful" to verdict.summaryFaithful,
            )
        }

        fun avg(sum: Double) = if (scored == 0) 0.0 else sum / scored
        val report = buildString {
            appendLine()
            appendLine("=== LLM-as-judge (gen=$genModel, judge=$judgeModel, n=$scored) ===")
            appendLine("  근거 일치(1-5)       avg ${"%.2f".format(avg(gnd))}")
            appendLine("  증상 구체성(1-5)     avg ${"%.2f".format(avg(spec))}")
            appendLine("  가독성(1-5)          avg ${"%.2f".format(avg(read))}")
            appendLine("  근거없는 단정 없음    ${if (scored == 0) "N/A" else "%.0f%%".format(noFabPass * 100.0 / scored)} (pass)")
            appendLine("  요약 Faithfulness    ${if (faithTot == 0) "N/A" else "%.0f%%".format(faithPass * 100.0 / faithTot)} (pass, n=$faithTot)")
            appendLine("--- 표본별 ---")
            lines.forEach { appendLine(it) }
            appendLine()
            appendLine("※ 이 점수는 사람 검증(30~50건) 전까지 참고치. judge 편향(장황함 선호·동일계열) 주의.")
        }
        println(report)
        java.io.File("build/judge-report.txt").apply { parentFile.mkdirs() }.writeText(report)
        // 요약 포함 사람 검증 시트를 만들 수 있도록 표본 원자료를 JSONL 로 저장(한 줄 = 한 표본).
        java.io.File("build/judge-samples.jsonl").apply { parentFile.mkdirs() }
            .writeText(records.joinToString("\n") { om.writeValueAsString(it) } + "\n")
    }

    private data class Verdict(
        val grounding: Int, val specificity: Int, val readability: Int,
        val noFabrication: String, val summaryFaithful: String,
    )

    private fun judge(
        caller: RestClientAnthropicCaller, model: String, om: ObjectMapper,
        paste: String, title: String, summary: String?,
    ): Verdict {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "grounding" to mapOf("type" to "integer", "description" to "근거 일치: 원문에 근거한 표현인가(1 전혀~5 완전)"),
                "specificity" to mapOf("type" to "integer", "description" to "증상 구체성: 다른 오류와 구분되는가(1~5)"),
                "readability" to mapOf("type" to "integer", "description" to "가독성: 쉽게 이해되는가(1~5)"),
                "no_fabrication" to mapOf("type" to "string", "enum" to listOf("pass", "fail"),
                    "description" to "근거 없는 단정이 없으면 pass, 원문에 없는 원인을 확정적으로 지어냈으면 fail"),
                "summary_faithful" to mapOf("type" to "string", "enum" to listOf("pass", "fail", "na"),
                    "description" to "요약이 원문 사실에만 근거하면 pass, 없는 내용을 단정하면 fail, 요약이 없으면 na"),
            ),
            "required" to listOf("grounding", "specificity", "readability", "no_fabrication", "summary_faithful"),
        )
        val user = buildString {
            append("[에러 원문]\n").append(paste).append("\n\n")
            append("[평가할 제목]\n").append(title).append("\n\n")
            append("[평가할 요약]\n").append(summary ?: "(없음)")
        }
        val body = mapOf(
            "model" to model,
            "max_tokens" to 300,
            "system" to "너는 에러 초안 품질의 엄격한 평가자다. 반드시 score_draft 툴만 호출한다. " +
                "원문에 적힌 사실만 근거로 채점하고, 길고 그럴듯한 문장에 속지 마라. 원문에 없는 원인을 제목·요약이 단정하면 낮게 준다.",
            "messages" to listOf(mapOf("role" to "user", "content" to user)),
            "tools" to listOf(mapOf("name" to "score_draft", "description" to "에러 초안 제목·요약을 rubric 으로 채점", "input_schema" to schema)),
            // 확장사고 모델(claude-sonnet-5-5 등)은 강제 tool_choice(tool/any) 미지원 → auto. system 으로 툴 사용 강제.
            "tool_choice" to mapOf("type" to "auto"),
        )
        val resp = caller.call(om.writeValueAsString(body))
        val root = om.readTree(resp)
        val tu = root.path("content")
            .firstOrNull { it.path("type").asText() == "tool_use" && it.path("name").asText() == "score_draft" }
        val inp = if (tu != null) tu.path("input") else {
            // 폴백: 모델이 tool 대신 텍스트로 JSON 을 낸 경우 본문에서 객체 추출
            val text = root.path("content").firstOrNull { it.path("type").asText() == "text" }?.path("text")?.asText()
                ?: error("no tool_use and no text")
            val j = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(text)?.value ?: error("no json in text")
            om.readTree(j)
        }
        return Verdict(
            grounding = inp.path("grounding").asInt(),
            specificity = inp.path("specificity").asInt(),
            readability = inp.path("readability").asInt(),
            noFabrication = inp.path("no_fabrication").asText(),
            summaryFaithful = inp.path("summary_faithful").asText(),
        )
    }
}
