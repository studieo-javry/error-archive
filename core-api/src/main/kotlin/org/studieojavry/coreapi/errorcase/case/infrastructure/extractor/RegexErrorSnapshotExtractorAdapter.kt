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

    /** 예외 헤더 접두(`Caused by:` / `Suppressed:` / `Exception in thread "..."`) — 떼어내 FQN 이 줄 선두에 오게 한다. */
    private val throwablePrefixRegex = Regex("""^(Caused by:\s*|Suppressed:\s*|Exception in thread\s+"[^"]*"\s+)""")

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
     * header 에서 **예외 선언 형태의 줄**만 골라 FQN·message 를 분리한다.
     * 흔한 패턴:
     *   `java.lang.IllegalStateException: foo bar`
     *   `Exception in thread "main" java.lang.IllegalStateException: foo`
     *   `Caused by: java.net.SocketTimeoutException: read timed out`
     *
     * 과대추출 방지: FQN 이 (접두 제거 후) 줄 **선두**에 오고 그 뒤가 줄 끝이거나 `:` 여야 예외로 본다.
     * → 문장 중간의 일반 타입명(`... to java.lang.String:`), 함수 호출(`debug.Stack()`),
     *   로거명(`c.e.OrderService - ...`) 은 예외로 잡지 않는다(null → UseCase 가 LLM/폴백으로 처리).
     * 여러 예외 줄이 있으면 뒤쪽(= Caused by 루트원인)을 우선한다.
     */
    private fun parseHeader(header: String): Pair<String?, String?> {
        if (header.isBlank()) return null to null
        val lines = header.lines().map { it.trim() }.filter { it.isNotBlank() }
        for (line in lines.asReversed()) {
            parseExceptionLine(line)?.let { return it }
        }
        return null to null
    }

    /** 한 줄이 `<FQN>` 또는 `<FQN>: <message>` 형태의 예외 선언이면 (class, message), 아니면 null. */
    private fun parseExceptionLine(line: String): Pair<String, String?>? {
        val stripped = line.replaceFirst(throwablePrefixRegex, "").trim()
        val match = fqnRegex.find(stripped) ?: return null
        if (match.range.first != 0) return null // FQN 이 줄 선두가 아님(문장 중간 타입명 등) → 예외 아님
        val after = stripped.substring(match.range.last + 1)
        // 줄 끝이거나 ':'(메시지) 만 허용. '(' 함수호출·' - ' 로거줄 등은 거부.
        if (after.isNotEmpty() && !after.startsWith(":")) return null
        val message = after.removePrefix(":").trim().takeIf { it.isNotBlank() }
        return match.value to message
    }

    private fun empty() = ErrorSnaphostExtractorPort.ExtractedSnapshot(null, null, null)
}
