package org.studieojavry.coreapi.errorcase.case.eval

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.case.application.port.DraftExtractionPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.SuggestErrorDraftUseCase
import org.studieojavry.coreapi.errorcase.case.infrastructure.extractor.RegexErrorSnapshotExtractorAdapter
import org.studieojavry.coreapi.errorcase.case.infrastructure.llm.NoOpDraftExtractionAdapter

/**
 * 에러 자동 구조화 **정확도 eval 하네스**.
 *
 * 라벨링된 데이터셋(`DraftExtractionDataset`)에 파이프라인을 돌려 지표를 측정·출력하고 회귀 플로어를 건다.
 * `DraftExtractionPort` 로 파라미터화되어 있어 **동일 하네스를 ① 정규식(NoOp) 베이스라인 ② 실제 LLM**
 * 어댑터에 그대로 재사용한다(LLM_API_KEY 설정 후 어댑터만 바꿔 재측정 → 설계 변경의 품질 개선을 수치로 증명).
 */
class DraftExtractionEvalTest {

    private val regex = RegexErrorSnapshotExtractorAdapter()

    @Test
    fun `정규식(결정적) 베이스라인 — exceptionClass·제목 측정, severity·tags 는 LLM 몫`() {
        val rep = evaluate(NoOpDraftExtractionAdapter())

        println(
            buildString {
                appendLine()
                appendLine("=== Draft Extraction Eval — 정규식 베이스라인 (NoOp, LLM 미사용) ===")
                appendLine("samples = ${rep.n}")
                appendLine("exceptionClass 정확도 = ${pct(rep.exAcc)}")
                appendLine("title 키워드 적중     = ${pct(rep.titleRate)}")
                appendLine("severity 정확도       = ${pct(rep.sevAcc)}   ← 정규식은 심각도 추론 불가(LLM 필요)")
                appendLine("tag precision        = ${pct(rep.tagPrecision)}   ← 정규식은 태그 생성 불가(LLM 필요)")
                appendLine("--- 표본별 ---")
                rep.lines.forEach { appendLine(it) }
            },
        )

        // 회귀 가드: 깨끗한 JVM 스택은 정규식이 잡아야 한다(베이스라인 하회 방지).
        assertTrue(rep.exAcc >= 0.5, "exceptionClass 정확도 베이스라인 하회: ${rep.exAcc}")
        assertTrue(rep.titleRate >= 0.5, "title 키워드 적중 베이스라인 하회: ${rep.titleRate}")
    }

    // --- 측정 로직 (extractor 교체로 LLM 평가에도 재사용) ---

    private data class Report(
        val n: Int,
        val exAcc: Double,
        val titleRate: Double,
        val sevAcc: Double,
        val tagPrecision: Double,
        val lines: List<String>,
    )

    private fun evaluate(extractor: DraftExtractionPort): Report {
        val uc = SuggestErrorDraftUseCase(regex, extractor)
        var exOk = 0
        var titleTot = 0; var titleOk = 0
        var sevTot = 0; var sevOk = 0
        var tagTot = 0; var tagOk = 0
        val lines = mutableListOf<String>()

        for (s in DraftExtractionDataset.samples) {
            val r = uc.suggest(SuggestErrorDraftUseCase.Command(s.paste))
            val exMatch = simpleName(r.exceptionClass) == simpleName(s.expectedExceptionClass)
            if (exMatch) exOk++
            s.titleMustContain?.let { kw -> titleTot++; if (r.title.contains(kw, ignoreCase = true)) titleOk++ }
            s.expectedSeverity?.let { exp -> sevTot++; if (r.severity == exp) sevOk++ }
            for (t in r.tags) { tagTot++; if (t in s.acceptableTags) tagOk++ }
            lines += "  [${if (exMatch) "O" else "X"}] ${s.id.padEnd(20)} ex=${r.exceptionClass ?: "-"}  sev=${r.severity ?: "-"}  tags=${r.tags}  degraded=${r.degraded}"
        }
        val n = DraftExtractionDataset.samples.size
        return Report(
            n = n,
            exAcc = exOk.toDouble() / n,
            titleRate = if (titleTot == 0) 0.0 else titleOk.toDouble() / titleTot,
            sevAcc = if (sevTot == 0) 0.0 else sevOk.toDouble() / sevTot,
            tagPrecision = if (tagTot == 0) 0.0 else tagOk.toDouble() / tagTot,
            lines = lines,
        )
    }

    private fun simpleName(fqnOrSimple: String?): String? =
        fqnOrSimple?.substringAfterLast('.')?.lowercase()?.takeIf { it.isNotBlank() }

    private fun pct(d: Double): String = "%.0f%%".format(d * 100)
}
