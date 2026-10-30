package org.studieojavry.coreapi.errorcase.case.eval

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.NoOpDraftExtractionAdapter

/**
 * 정규식(결정적) 베이스라인 eval — 항상 돈다. exceptionClass·title 측정(severity·tags 는 LLM 몫).
 * dev 세트(기존 8건, `dataset-8.jsonl`) 사용. LLM 비교는 `DraftExtractionLlmEvalTest`(LLM_API_KEY 있을 때만).
 */
class DraftExtractionEvalTest {

    @Test
    fun `정규식(결정적) 베이스라인 측정 — dev 세트`() {
        val rep = DraftEval.run(NoOpDraftExtractionAdapter(), EvalDataset.dev)

        println(
            buildString {
                appendLine()
                appendLine("=== Draft Extraction Eval — 정규식 베이스라인 (NoOp) · dev ${rep.n}건 ===")
                appendLine("exceptionClass 정확도 = ${DraftEval.pct(rep.exAcc)}")
                appendLine("title 키워드 적중     = ${DraftEval.pct(rep.titleRate)}")
                appendLine("severity exact/accept = ${DraftEval.pct(rep.sevExact)} / ${DraftEval.pct(rep.sevAcceptable)}  ← 정규식은 심각도 추론 불가")
                appendLine("tag precision/cover   = ${DraftEval.pctN(rep.tagPrecision)} / ${DraftEval.pct(rep.tagCoverage)}  ← 정규식은 태그 생성 불가")
                appendLine("--- 표본별 ---")
                rep.lines.forEach { appendLine(it) }
            },
        )

        assertTrue(rep.exAcc >= 0.5, "exceptionClass 정확도 베이스라인 하회: ${rep.exAcc}")
        assertTrue(rep.titleRate >= 0.5, "title 키워드 적중 베이스라인 하회: ${rep.titleRate}")
    }
}
