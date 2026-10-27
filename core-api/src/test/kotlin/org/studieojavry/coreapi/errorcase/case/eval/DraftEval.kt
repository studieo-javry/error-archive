package org.studieojavry.coreapi.errorcase.case.eval

import org.studieojavry.coreapi.errorcase.case.application.port.DraftExtractionPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.SuggestErrorDraftUseCase
import org.studieojavry.coreapi.errorcase.case.infrastructure.extractor.RegexErrorSnapshotExtractorAdapter

/**
 * eval 측정 공용 로직 — 정규식 베이스라인/LLM 어댑터 둘 다 같은 하네스로 돌린다.
 * `DraftExtractionPort` 만 바꿔 넣으면 정규식 vs LLM 비교가 된다.
 */
object DraftEval {

    private val regex = RegexErrorSnapshotExtractorAdapter()

    data class Report(
        val n: Int,
        val exAcc: Double,
        val titleRate: Double,
        val sevAcc: Double,
        val tagPrecision: Double,
        val lines: List<String>,
    )

    fun run(extractor: DraftExtractionPort): Report {
        val uc = SuggestErrorDraftUseCase(regex, extractor)
        var exOk = 0
        var titleTot = 0; var titleOk = 0
        var sevTot = 0; var sevOk = 0
        var tagTot = 0; var tagOk = 0
        val lines = mutableListOf<String>()

        for (s in DraftExtractionDataset.samples) {
            val r = uc.suggest(SuggestErrorDraftUseCase.Command(s.paste))
            val actualSimple = simpleName(r.exceptionClass)
            val exMatch = if (s.acceptableExceptionClasses.isEmpty()) actualSimple == null
                else actualSimple != null && actualSimple in s.acceptableExceptionClasses
            if (exMatch) exOk++
            if (s.titleAnyOf.isNotEmpty()) { titleTot++; if (s.titleAnyOf.any { r.title.contains(it, ignoreCase = true) }) titleOk++ }
            s.expectedSeverity?.let { exp -> sevTot++; if (r.severity == exp) sevOk++ }
            for (t in r.tags) { tagTot++; if (t in s.acceptableTags) tagOk++ }
            lines += "  [${if (exMatch) "O" else "X"}] ${s.id.padEnd(20)} ex=${r.exceptionClass ?: "-"}  sev=${r.severity ?: "-"}  tags=${r.tags}  degraded=${r.degraded}"
        }
        val n = DraftExtractionDataset.samples.size
        return Report(n, exOk.toDouble() / n, rate(titleOk, titleTot), rate(sevOk, sevTot), rate(tagOk, tagTot), lines)
    }

    fun pct(d: Double): String = "%.0f%%".format(d * 100)

    private fun rate(ok: Int, tot: Int): Double = if (tot == 0) 0.0 else ok.toDouble() / tot

    private fun simpleName(fqnOrSimple: String?): String? =
        fqnOrSimple?.substringAfterLast('.')?.lowercase()?.takeIf { it.isNotBlank() }
}
