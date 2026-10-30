package org.studieojavry.coreapi.errorcase.case.eval

import com.fasterxml.jackson.databind.ObjectMapper
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Severity
import java.io.File

/**
 * eval 라벨 샘플 — Phase 0 스키마(`docs/llm-eval-phase0-spec.md`).
 * `resources/eval` 의 모든 jsonl(한 줄당 한 객체)을 읽어 `split`(dev/test)으로 나눈다.
 * 새 배치는 `dataset-batchN.jsonl` 로 떨어뜨리기만 하면 자동 포함. 모델에는 `paste`만 전달.
 */
data class EvalSample(
    val id: String,
    val split: String,          // dev(튜닝) | test(봉인)
    val source: String,
    val lang: String,
    val family: String,
    val shape: String,
    val paste: String,
    /** 허용 예외 simple name(소문자). 빈 집합 = "명시적 예외 클래스 없음"(null 기대). */
    val exceptionClasses: Set<String>,
    val titleAnyOf: Set<String>,
    val tagsExpected: Set<String>,
    val tagsAcceptable: Set<String>,
    val severity: Severity?,            // canonical
    val severityAcceptable: Set<Severity>,
)

object EvalDataset {
    private val om = ObjectMapper()

    /** classpath `eval` 디렉토리의 모든 jsonl 을 읽는다(파일명 정렬). */
    private fun all(): List<EvalSample> {
        val url = EvalDataset::class.java.classLoader.getResource("eval")
            ?: error("eval resource dir not found on classpath")
        val files = File(url.toURI()).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?: error("no eval/*.jsonl files")
        return files.sortedBy { it.name }.flatMap { f ->
            f.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.map(::parse).toList() }
        }
    }

    /** 튜닝 세트(split=dev). */
    val dev: List<EvalSample> get() = all().filter { it.split == "dev" }

    /** 봉인 벤치마크(split=test). */
    val test: List<EvalSample> get() = all().filter { it.split == "test" }

    private fun parse(line: String): EvalSample {
        val n = om.readTree(line)
        val s = n.get("strata")
        val l = n.get("labels")
        fun arr(node: com.fasterxml.jackson.databind.JsonNode?, lower: Boolean = false): Set<String> =
            node?.map { if (lower) it.asText().lowercase() else it.asText() }?.toSet() ?: emptySet()
        return EvalSample(
            id = n.get("id").asText(),
            split = n.get("split").asText(),
            source = n.get("source").asText(),
            lang = s.get("lang").asText(),
            family = s.get("family").asText(),
            shape = s.get("shape").asText(),
            paste = n.get("paste").asText(),
            exceptionClasses = arr(l.get("exceptionClasses"), lower = true),
            titleAnyOf = arr(l.get("titleAnyOf")),
            tagsExpected = arr(l.get("tagsExpected"), lower = true),
            tagsAcceptable = arr(l.get("tagsAcceptable"), lower = true),
            severity = l.get("severity")?.takeIf { !it.isNull }?.let { Severity.valueOf(it.asText()) },
            severityAcceptable = (l.get("severityAcceptable")?.map { Severity.valueOf(it.asText()) } ?: emptyList()).toSet(),
        )
    }
}
