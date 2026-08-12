package org.studieojavry.coreapi.errorcase.case.infrastructure.extractor

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorSnaphostExtractorPort

/**
 * 단순 휴리스틱 기반 stack trace 파서.
 * Java/Kotlin 의 표준 형태(`com.foo.Bar: 메시지\n\tat ...`) 를 가정.
 *
 * - 첫 번째 `at ` 라인 이후를 stack trace 로 본다
 * - 그 앞쪽에서 `<FQN>: <message>` 패턴 또는 단독 FQN 을 exceptionClass + message 로 추출
 * - 파싱 실패 시 모두 null — useCase 가 null safe 하게 처리
 */
@Component
class RegexErrorSnapshotExtractorAdapter : ErrorSnaphostExtractorPort {

    /** `com.foo.bar.Baz` 또는 `com.foo.Baz$Inner` 같은 FQN 매칭 */
    private val fqnRegex = Regex("""([a-zA-Z_$][\w$]*\.)+[A-Z][\w$]*""")
    private val atLineRegex = Regex("""^\s*at\s+.+""", RegexOption.MULTILINE)

    override fun extract(source: String): ErrorSnaphostExtractorPort.ExtractedSnapshot {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return empty()

        val firstAtMatch = atLineRegex.find(trimmed)
        val (header, stackTrace) = if (firstAtMatch != null) {
            val headerEnd = firstAtMatch.range.first
            trimmed.substring(0, headerEnd).trim() to trimmed.substring(headerEnd).trim()
        } else {
            trimmed to null
        }

        val (exClass, exMsg) = parseHeader(header)

        return ErrorSnaphostExtractorPort.ExtractedSnapshot(
            exceptionClass = exClass,
            exceptionMessage = exMsg,
            rawStackTrace = stackTrace?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * header 의 첫 줄에서 FQN 과 message 를 분리.
     * 흔한 패턴:
     *   `java.lang.IllegalStateException: foo bar`
     *   `Exception in thread "main" java.lang.IllegalStateException: foo`
     *   `Caused by: java.net.SocketTimeoutException: read timed out`
     */
    private fun parseHeader(header: String): Pair<String?, String?> {
        if (header.isBlank()) return null to null

        // 의미 있는 첫 줄을 찾는다 (Caused by / Exception in thread 라인은 우선순위 낮음).
        val lines = header.lines().map { it.trim() }.filter { it.isNotBlank() }
        val target = lines.lastOrNull { fqnRegex.containsMatchIn(it) } ?: lines.firstOrNull()
        target ?: return null to null

        val match = fqnRegex.find(target) ?: return null to target
        val fqn = match.value
        val tail = target.substring(match.range.last + 1).trim()
        val message = tail.removePrefix(":").trim().takeIf { it.isNotBlank() }
        return fqn to message
    }

    private fun empty() = ErrorSnaphostExtractorPort.ExtractedSnapshot(null, null, null)
}
