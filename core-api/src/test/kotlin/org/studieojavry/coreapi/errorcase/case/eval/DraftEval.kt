package org.studieojavry.coreapi.errorcase.case.eval

import org.studieojavry.coreapi.errorcase.case.application.port.DraftExtractionPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.SuggestErrorDraftUseCase
import org.studieojavry.coreapi.errorcase.case.infrastructure.extractor.RegexErrorSnapshotExtractorAdapter

/**
 * eval 측정 공용 로직 — `DraftExtractionPort` 만 바꿔 regex(NoOp)/LLM/LLM+few-shot 를 **같은 하네스·같은 데이터셋**으로 돌린다.
 * 지표(Phase 0 §6): exceptionClass 정확도(허용집합/null), title any-of, severity **exact+acceptable**,
 *   태그 **micro precision + core coverage**(별칭 정규화 후), 빈 태그율.
 * 주의: 이 하네스는 정규식 힌트·병합·검증을 포함한 **최종 UseCase 경로**를 잰다(= 사용자 체감면).
 *   regex 단독면은 NoOp 어댑터로, LLM 원출력면은 별도 측정이 필요(§6.3).
 */
object DraftEval {

    private val regex = RegexErrorSnapshotExtractorAdapter()

    /** 평가용 태그 별칭 정규화(생산 응답은 바꾸지 않음). Phase 0 §6.2. */
    private val tagAlias = mapOf(
        "nodejs" to "node", "node.js" to "node",
        "js" to "javascript",
        "golang" to "go",
        "db" to "database",
        "postgres" to "postgresql", "pg" to "postgresql",
        "spring-framework" to "spring", "springframework" to "spring",
        "springboot" to "spring-boot",
        "configuration" to "config",
        "deprecated" to "deprecation",
        "out-of-memory" to "oom",
        "connection-refused" to "econnrefused",
        "nil-pointer-dereference" to "nil-pointer", "nil" to "nil-pointer",
        "deadlineexceeded" to "timeout", "deadline-exceeded" to "timeout", "deadline" to "timeout",
        "validationerror" to "validation",
    )

    private fun norm(t: String) = tagAlias[t.lowercase()] ?: t.lowercase()
    private fun normSet(ts: Collection<String>) = ts.map(::norm).toSet()

    data class Report(
        val n: Int,
        val exAcc: Double,
        val titleRate: Double,
        val sevExact: Double,
        val sevAcceptable: Double,
        /** null = 예측 태그 0(분모 0) → N/A. */
        val tagPrecision: Double?,
        val tagCoverage: Double,
        val emptyTagRate: Double,
        val lines: List<String>,
    )

    fun run(extractor: DraftExtractionPort, samples: List<EvalSample>): Report {
        val uc = SuggestErrorDraftUseCase(regex, extractor)
        var exOk = 0
        var titleTot = 0; var titleOk = 0
        var sevTot = 0; var sevExactOk = 0; var sevAccOk = 0
        var predTot = 0; var predHit = 0     // precision 분모/분자
        var expTot = 0; var expHit = 0       // coverage 분모/분자
        var emptyTag = 0
        val lines = mutableListOf<String>()

        for (s in samples) {
            val r = uc.suggest(SuggestErrorDraftUseCase.Command(s.paste))

            val actual = simpleName(r.exceptionClass)
            val exMatch = if (s.exceptionClasses.isEmpty()) actual == null
                          else actual != null && actual in s.exceptionClasses
            if (exMatch) exOk++

            if (s.titleAnyOf.isNotEmpty()) {
                titleTot++
                if (s.titleAnyOf.any { r.title.contains(it, ignoreCase = true) }) titleOk++
            }

            if (s.severity != null) {
                sevTot++
                if (r.severity == s.severity) sevExactOk++
                if (r.severity != null && r.severity in s.severityAcceptable) sevAccOk++
            }

            val pred = normSet(r.tags)
            val acc = normSet(s.tagsAcceptable)
            val exp = normSet(s.tagsExpected)
            if (pred.isEmpty()) emptyTag++
            predTot += pred.size; predHit += pred.count { it in acc }
            expTot += exp.size; expHit += exp.count { it in pred }

            val sevCell = "${r.severity?.name ?: "-"}/${s.severity?.name ?: "-"}"
            lines += "  [${if (exMatch) "O" else "X"}] ${s.id.padEnd(28)} ex=${(r.exceptionClass ?: "-").padEnd(42)} sev=${sevCell.padEnd(7)} deg=${r.degraded} tags=${r.tags}"
        }

        val n = samples.size
        return Report(
            n = n,
            exAcc = exOk.toDouble() / n,
            titleRate = rate(titleOk, titleTot),
            sevExact = rate(sevExactOk, sevTot),
            sevAcceptable = rate(sevAccOk, sevTot),
            tagPrecision = if (predTot == 0) null else predHit.toDouble() / predTot,
            tagCoverage = rate(expHit, expTot),
            emptyTagRate = rate(emptyTag, n),
            lines = lines,
        )
    }

    fun pct(d: Double): String = "%.0f%%".format(d * 100)
    fun pctN(d: Double?): String = d?.let { pct(it) } ?: "N/A"

    private fun rate(ok: Int, tot: Int): Double = if (tot == 0) 0.0 else ok.toDouble() / tot

    private fun simpleName(fqnOrSimple: String?): String? =
        fqnOrSimple?.substringAfterLast('.')?.lowercase()?.takeIf { it.isNotBlank() }
}
