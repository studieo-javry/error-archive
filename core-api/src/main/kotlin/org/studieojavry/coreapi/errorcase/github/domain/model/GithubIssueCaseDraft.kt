package org.studieojavry.coreapi.errorcase.github.domain.model

import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import java.time.Instant

/**
 * GitHub Issue → Case 초안. 사용자가 미리보기에서 고친 값이 있으면 그 값이 우선한다.
 *
 * Issue 를 통째로 옮기지 않고 **Case 칸의 의미에 맞는 것만** 채운다.
 * - title: Issue 제목(200자 컷)
 * - paste(Error Trace): **에러 로그로 보이는 코드 블록만**(예외 클래스·스택 프레임·Traceback·panic, 또는 로그 섹션의 에러 줄).
 *   재현 명령·설정·예시 코드는 넣지 않는다 — 이 칸은 예외 추출·유사 Case 지문의 재료라서.
 * - description: 본문에서 Error Trace 로 옮긴 블록 자리는 안내 한 줄로 바꾸고, 체크리스트만 있는 섹션·`_No response_` 섹션은 뺀다. 끝에 출처 한 줄
 * - tags: **자동으로 넣지 않는다**(빈 목록). 라벨은 [tagSuggestions] 로만 — 상태·종류·우선순위 라벨은 빼고 `in: web` 같은 주제 라벨은 값만
 * - severity: 넣지 않고 우선순위/심각도 라벨이 있으면 [severitySuggestion] 으로만
 * - occurredAt: 넣지 않는다. Issue 작성 시각은 "발생"이 아니라 "보고" 시각이라 [reportedAt] 으로만 보여 준다
 * - 우회 방법(Workaround) 섹션 → [workaroundStep] 후보(진행 중). 사용자가 고르면 첫 Step 으로 만든다
 */
data class GithubIssueCaseDraft(
    val title: String,
    val description: String,
    val paste: String?,
    val tags: List<String>,
    val reportedAt: Instant,
    val codeBlocks: List<CodeBlock>,
    val tagSuggestions: List<String>,
    val severitySuggestion: SeveritySuggestion?,
    val workaroundStep: StepSuggestion?,
) {
    /** 본문 코드 블록 하나. [errorLog] = Error Trace 에 넣었는지(미리보기에서 켜고 끄는 기본값). */
    data class CodeBlock(val section: String?, val language: String?, val text: String, val errorLog: Boolean)

    /** [code] 1(Outage)~4(Info). [fromLabel] = 근거 라벨 원문. */
    data class SeveritySuggestion(val code: Int, val fromLabel: String)

    data class StepSuggestion(val title: String, val body: String)

    companion object {
        private const val TITLE_MAX = 200
        private const val MOVED_TO_TRACE = "_(에러 로그 → Error Trace 로 옮김)_"
        private const val NO_RESPONSE = "_No response_"

        // ── 에러 로그 판별 ──────────────────────────────────
        // 예외 이름이 "나온" 것만으론 부족(코드의 import·throws·catch 도 이름을 쓴다) → 로그에 찍히는 모양일 때만.
        /** 줄 첫머리의 `pkg.FooException: 메시지`, `Caused by: …`, `Exception in thread "…" …`, `Uncaught TypeError: …`. */
        private val EXCEPTION_LINE = Regex(
            """(?m)^\s*(?:Caused by:\s*|Exception in thread "[^"]*"\s*|Uncaught (?:\(in promise\)\s*)?)?(?:[A-Za-z_$][\w$]*\.)*[A-Z][\w$]*(?:Exception|Error|Throwable)(?::|\s*$)""",
        )
        /** Java `at a.b.C.m(C.java:12)` / JS `at fn (file.js:1:2)` · `at node:internal/…:3:4`. */
        private val STACK_FRAME = Regex(
            """(?m)^\s*at\s+(?:\S+\s*\((?:[^()]*:\d+(?::\d+)?|Native Method|Unknown Source)\)|(?:file:|node:|/|[A-Za-z]:\\)\S*:\d+(?::\d+)?)\s*$""",
        )
        /** 로그 레벨 줄: Spring `… ERROR 123 --- [main] …`, `[ERROR] …`, `ERROR: …`. */
        private val ERROR_LEVEL_LINE = Regex("""(?m)(?:\s(?:ERROR|FATAL)\s+\d*\s*---\s|^\s*\[?(?:ERROR|FATAL)\]?[\s:])""")
        private val PY_TRACEBACK = Regex("""Traceback \(most recent call last\)""")
        private val GO_PANIC = Regex("""(?m)^(?:panic: |goroutine \d+ \[)""")
        private val ERROR_WORD = Regex("""(?i)\b(?:error|exception|fail(?:ed|ure)?|fatal|panic)\b""")
        private val LOG_SECTION = Regex("""(?i)(?:\blogs?\b|stack\s*trace|traceback|\berror(?:\s+output|\s+message)?\b|\boutput\b|에러|오류|로그)""")
        private val WORKAROUND_SECTION = Regex("""(?i)(?:workaround|work-around|임시\s*해결|우회)""")

        // ── 섹션 · 코드 블록 ────────────────────────────────
        private val HEADING = Regex("""^#{1,6}\s+(.+?)\s*#*\s*$""")
        private val FENCE_OPEN = Regex("""^\s{0,3}(```+|~~~+)\s*([\w+#.-]*).*$""")
        private val TASK_ITEM = Regex("""^\s*[-*]\s+\[[ xX]]\s+""")

        // ── 라벨 ───────────────────────────────────────────
        /** 업무 상태·분류용 — 기술 분류(태그)가 아님. */
        private val META_LABELS = setOf(
            "bug", "enhancement", "feature", "feature request", "question", "duplicate", "invalid", "wontfix", "won't fix",
            "documentation", "docs", "help wanted", "good first issue", "triage", "needs triage", "pending triage",
            "stale", "regression", "dependencies", "breaking change", "discussion", "task", "chore", "test",
            "contribution welcome", "has workaround", "needs reproduction", "needs repro", "upstream", "blocked", "on hold",
        )
        /** `접두사: 값` 중 버리는 접두사(상태·종류·우선순위·처리). */
        private val META_PREFIXES = setOf(
            "status", "type", "kind", "priority", "prio", "severity", "sev", "needs", "for", "resolution", "triage", "stale", "size", "effort",
            "bug", "clarity", "difficulty", "good first", "help",
        )
        /** `접두사: 값` 중 값이 기술 주제인 접두사 → 값만 태그 후보로. */
        private val TOPIC_PREFIXES = setOf(
            "in", "area", "theme", "feat", "component", "comp", "module", "lang", "language", "topic", "scope", "platform", "os", "pkg", "package",
        )
        private val LABEL_SPLIT = Regex("""^\s*([^:/]+?)\s*[:/]\s*(.+)$""")
        private val PRIORITY_P = Regex("""(?i)^p([0-4])(?:[-_ ].*)?$""")
        private val SEVERITY_S = Regex("""(?i)^(?:sev|s)([1-4])$""")
        private val PRIORITY_WORDS = mapOf(
            "critical" to 1, "blocker" to 1, "urgent" to 1, "high" to 2, "major" to 2,
            "medium" to 3, "normal" to 3, "low" to 4, "minor" to 4, "trivial" to 4,
        )
        private val SEVERITY_PREFIXES = setOf("priority", "prio", "severity", "sev")

        fun from(issue: GithubIssueSnapshot, body: String?, labels: List<String>, repoFullName: String): GithubIssueCaseDraft {
            val sections = parseSections(body?.replace("\r\n", "\n")?.trim().orEmpty())
            val blocks = sections.flatMap { s -> s.blocks.map { b -> CodeBlock(s.heading, b.language, b.text, isErrorLog(b.text, s.heading)) } }
            val unfencedLogs = sections.filter { it.isUnfencedErrorLog() }.map { it.plainText().trim() }
            val paste = (blocks.filter { it.errorLog }.map { it.text } + unfencedLogs).joinToString("\n\n").ifBlank { null }

            val source = "> GitHub Issue: $repoFullName#${issue.number}"
            val described = renderDescription(sections)
            return GithubIssueCaseDraft(
                title = issue.title.trim().ifBlank { "GitHub Issue #${issue.number}" }.take(TITLE_MAX),
                description = if (described.isEmpty()) source else "$described\n\n$source",
                paste = paste,
                tags = emptyList(),
                reportedAt = issue.createdAtGh,
                codeBlocks = blocks,
                tagSuggestions = suggestTags(labels),
                severitySuggestion = suggestSeverity(labels),
                workaroundStep = sections
                    .firstOrNull { s -> s.heading?.let { WORKAROUND_SECTION.containsMatchIn(it) } == true }
                    ?.rawContent()?.trim()
                    ?.takeIf { it.isNotEmpty() && it != NO_RESPONSE }
                    ?.let { StepSuggestion("우회 방법 (GitHub Issue #${issue.number})", it) },
            )
        }

        fun isErrorLog(text: String, sectionHeading: String?): Boolean =
            hasStrongErrorSignal(text) || (isLogSection(sectionHeading) && ERROR_WORD.containsMatchIn(text))

        private fun isLogSection(heading: String?) = heading?.let { LOG_SECTION.containsMatchIn(it) } == true

        private fun hasStrongErrorSignal(text: String): Boolean =
            EXCEPTION_LINE.containsMatchIn(text) || STACK_FRAME.containsMatchIn(text) || ERROR_LEVEL_LINE.containsMatchIn(text) ||
                PY_TRACEBACK.containsMatchIn(text) || GO_PANIC.containsMatchIn(text)

        /** 라벨 → 태그 후보. 상태·종류·우선순위 라벨은 버리고, 주제 접두사는 값만. 정규화 규칙은 Case 태그와 같다. */
        fun suggestTags(labels: List<String>): List<String> =
            labels.mapNotNull { raw ->
                val label = raw.trim().lowercase()
                if (label.isEmpty() || label in META_LABELS || PRIORITY_P.matches(label) || SEVERITY_S.matches(label)) return@mapNotNull null
                val split = LABEL_SPLIT.matchEntire(label) ?: return@mapNotNull label
                val prefix = split.groupValues[1].trim()
                when (prefix) {
                    in META_PREFIXES -> null
                    in TOPIC_PREFIXES -> split.groupValues[2].trim()
                    else -> label
                }
            }
                .filter { it.isNotEmpty() && it.length <= ErrorCase.TAG_MAX_LENGTH && it !in META_LABELS }
                .distinct()
                .take(ErrorCase.TAGS_MAX_PER_CASE)

        /** 우선순위/심각도 라벨 → 심각도 후보. 여러 개면 가장 심한 쪽. */
        fun suggestSeverity(labels: List<String>): SeveritySuggestion? =
            labels.mapNotNull { raw ->
                val label = raw.trim().lowercase()
                val prefixed = LABEL_SPLIT.matchEntire(label)?.takeIf { it.groupValues[1].trim() in SEVERITY_PREFIXES }
                val value = prefixed?.groupValues?.get(2)?.trim() ?: label
                val code = (if (prefixed != null) PRIORITY_WORDS[value] else null)
                    ?: PRIORITY_P.matchEntire(value)?.groupValues?.get(1)?.toInt()?.coerceIn(1, 4)
                    ?: SEVERITY_S.matchEntire(value)?.groupValues?.get(1)?.toInt()
                code?.let { SeveritySuggestion(it, raw.trim()) }
            }.minByOrNull { it.code }

        // ── 본문 파싱 ──────────────────────────────────────
        private class Fenced(val language: String?, val text: String)

        /** 섹션 안의 조각: 일반 줄 또는 코드 블록. 순서 유지(설명 재구성용). */
        private sealed interface Part {
            data class Line(val text: String) : Part
            class Block(val raw: String, val fenced: Fenced) : Part
        }

        private class Section(val heading: String?, val headingLine: String?, val parts: List<Part>) {
            val blocks: List<Fenced> get() = parts.filterIsInstance<Part.Block>().map { it.fenced }
            fun plainText(): String = parts.filterIsInstance<Part.Line>().joinToString("\n") { it.text }
            fun rawContent(): String = parts.joinToString("\n") { if (it is Part.Line) it.text else (it as Part.Block).raw }

            /** 코드 블록 없이 로그 섹션에 에러가 그냥 붙여넣어진 경우(Issue Forms 의 `render: shell` 이 아닌 칸 등). */
            fun isUnfencedErrorLog(): Boolean =
                isLogSection(heading) && blocks.isEmpty() && plainText().trim().let { it != NO_RESPONSE && hasStrongErrorSignal(it) }
        }

        /** 코드 블록 안의 `#` 줄을 제목으로 보지 않도록 펜스를 따라가며 나눈다. 닫히지 않은 펜스는 일반 줄로 둔다. */
        private fun parseSections(markdown: String): List<Section> {
            val sections = mutableListOf<Section>()
            var heading: String? = null
            var headingLine: String? = null
            var parts = mutableListOf<Part>()
            val lines = markdown.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val fence = FENCE_OPEN.matchEntire(line)
                if (fence != null) {
                    val marker = fence.groupValues[1]
                    val close = (i + 1 until lines.size).firstOrNull { j ->
                        val t = lines[j].trim()
                        t.startsWith(marker) && t.trimStart(marker[0]).isBlank()
                    }
                    if (close != null) {
                        val text = lines.subList(i + 1, close).joinToString("\n").trimEnd()
                        if (text.isNotBlank()) {
                            parts += Part.Block(lines.subList(i, close + 1).joinToString("\n"), Fenced(fence.groupValues[2].ifBlank { null }, text))
                        }
                        i = close + 1
                        continue
                    }
                }
                val h = HEADING.matchEntire(line)
                if (h != null) {
                    if (heading != null || parts.isNotEmpty()) sections += Section(heading, headingLine, parts)
                    heading = h.groupValues[1].trim()
                    headingLine = line.trim()
                    parts = mutableListOf()
                } else {
                    parts += Part.Line(line)
                }
                i++
            }
            if (heading != null || parts.isNotEmpty()) sections += Section(heading, headingLine, parts)
            return sections
        }

        private fun renderDescription(sections: List<Section>): String =
            sections.mapNotNull { s ->
                val content = s.parts.filter { it !is Part.Line || it.text.isNotBlank() }
                val droppable = content.isEmpty() ||
                    content.all { it is Part.Line && it.text.trim() == NO_RESPONSE } ||
                    content.all { it is Part.Line && TASK_ITEM.containsMatchIn(it.text) }
                if (s.heading != null && droppable) return@mapNotNull null
                val body = if (s.isUnfencedErrorLog()) MOVED_TO_TRACE else s.parts.joinToString("\n") { p ->
                    when (p) {
                        is Part.Line -> p.text
                        is Part.Block -> if (isErrorLog(p.fenced.text, s.heading)) MOVED_TO_TRACE else p.raw
                    }
                }.trim()
                listOfNotNull(s.headingLine, body.ifEmpty { null }).joinToString("\n\n").ifEmpty { null }
            }.joinToString("\n\n").trim()
    }
}
