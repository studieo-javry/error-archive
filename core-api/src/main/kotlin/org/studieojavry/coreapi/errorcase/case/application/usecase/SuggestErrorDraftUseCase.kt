package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.studieojavry.coreapi.errorcase.case.application.port.DraftExtractionPort
import org.studieojavry.coreapi.errorcase.case.application.port.DraftRateLimiterPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorSnaphostExtractorPort
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Severity

/**
 * 비정형 에러 로그(raw paste) → New Entry 초안(제목·예외클래스·요약·태그·심각도).
 *
 * 설계 원칙:
 *  - **사실은 정규식, 해석은 LLM.** exceptionClass/stacktrace 는 결정적 정규식(기존 포트)을 신뢰하고,
 *    title/summary/tags/severity 처럼 판단이 필요한 것만 LLM 에게 맡긴다.
 *  - **graceful degradation.** LLM(DraftExtractionPort)이 null/예외면 정규식 기반 폴백으로 결과를 낸다
 *    (`degraded=true`). 기능이 폼을 막지 않는다.
 *  - **외부로 보내기 전 마스킹·절삭.** 토큰 비용·프라이버시.
 *  - 자동 저장 X — 결과는 폼 프리필용이고 사용자가 검수·확정한다(human-in-the-loop, 호출부 책임).
 */
@Service
class SuggestErrorDraftUseCase(
    private val snapshotExtractor: ErrorSnaphostExtractorPort,
    private val draftExtractor: DraftExtractionPort,
    private val rateLimiter: DraftRateLimiterPort,
) {

    data class Command(
        val paste: String,
        val workspaceTags: List<String> = emptyList(),
        /** 비용·남용 방어용 호출자 식별. 컨트롤러가 인증 주체로 채운다(미지정 0 = 비요금 경로: eval/테스트). */
        val userId: Long = 0L,
    )

    data class Result(
        val title: String,
        val exceptionClass: String?,
        val summary: String?,
        val tags: List<String>,
        val severity: Severity?,
        /** true = LLM 미사용/실패 → 정규식 기반 폴백본. */
        val degraded: Boolean,
    )

    fun suggest(command: Command): Result {
        // 0) 사용자별 쿼터/버스트 — 초과면 요청 거절(429). 정규식 추출보다 먼저 막아 비용·CPU 둘 다 보호.
        rateLimiter.checkUser(command.userId).let {
            if (!it.allowed) throw DraftRateLimitExceededException(it.retryAfterSeconds, it.reason ?: "rate-limit")
        }

        val scrubbed = scrubAndTrim(command.paste)

        // 1) 사실(결정적): 정규식 추출
        val snapshot = snapshotExtractor.extract(scrubbed)

        // 2) 해석(비결정적): LLM — 전역 일일 예산이 남았을 때만 호출(소진 시 생략 → 폴백). 실패는 삼켜서 폴백으로.
        val llm: DraftExtractionPort.Output? =
            if (rateLimiter.tryConsumeGlobalBudget()) {
                runCatching {
                    draftExtractor.extract(
                        DraftExtractionPort.Input(
                            maskedPaste = scrubbed,
                            exceptionClassHint = snapshot.exceptionClass,
                            workspaceTags = command.workspaceTags,
                        ),
                    )
                }.getOrNull()
            } else {
                null // 전역 예산 소진 → LLM 생략(degraded), 서비스는 정규식 폴백으로 유지
            }

        // 3) 병합·검증: 사실은 정규식 우선, 해석은 LLM, LLM 없으면 폴백
        val exceptionClass = snapshot.exceptionClass ?: llm?.exceptionClass
        val title = clamp((llm?.title ?: fallbackTitle(exceptionClass, snapshot.exceptionMessage, scrubbed)).trim().ifBlank { DEFAULT_TITLE }, TITLE_MAX)
        val summary = llm?.summary?.trim()?.takeIf { it.isNotBlank() }?.let { clamp(it, SUMMARY_MAX) }
        val tags = normalizeTags(llm?.tags ?: emptyList())
        val severity = parseSeverity(llm?.severity)

        return Result(
            title = title,
            exceptionClass = exceptionClass,
            summary = summary,
            tags = tags,
            severity = severity,
            degraded = (llm == null),
        )
    }

    /** 민감정보 마스킹 + 길이 절삭(스택 head+tail). 외부 LLM 호출 비용·프라이버시 방어. */
    private fun scrubAndTrim(raw: String): String {
        var s = raw.trim()
        for (re in SECRET_PATTERNS) s = re.replace(s, REDACTED)
        val lines = s.lines()
        if (lines.size <= HEAD_LINES + TAIL_LINES) return s
        val omitted = lines.size - HEAD_LINES - TAIL_LINES
        return (lines.take(HEAD_LINES) + "… ($omitted lines trimmed) …" + lines.takeLast(TAIL_LINES)).joinToString("\n")
    }

    private fun fallbackTitle(exClass: String?, exMsg: String?, paste: String): String {
        val simple = exClass?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
        return when {
            simple != null && !exMsg.isNullOrBlank() -> "$simple: $exMsg"
            simple != null -> "$simple 발생"
            else -> paste.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: DEFAULT_TITLE
        }
    }

    private fun clamp(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    /** 기존 태그 규칙과 동일 사상: trim·lowercase·≤32자·중복제거. 제안은 보수적으로 ≤5개. */
    private fun normalizeTags(tags: List<String>): List<String> =
        tags.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.length <= TAG_MAX }
            .distinct()
            .take(TAGS_MAX)
            .toList()

    private fun parseSeverity(raw: String?): Severity? =
        raw?.trim()?.uppercase()?.let { v -> Severity.entries.firstOrNull { it.name == v } }

    companion object {
        private const val DEFAULT_TITLE = "새 에러"
        private const val TITLE_MAX = 120
        private const val SUMMARY_MAX = 200
        private const val TAG_MAX = 32
        private const val TAGS_MAX = 5
        private const val HEAD_LINES = 60
        private const val TAIL_LINES = 40
        private const val REDACTED = "«redacted»"
        private val SECRET_PATTERNS = listOf(
            Regex("""(?i)bearer\s+[A-Za-z0-9._\-]+"""),
            Regex("""sk-[A-Za-z0-9]{10,}"""),
            Regex("""(?i)(password|passwd|secret|token|api[_-]?key)\s*[=:]\s*\S+"""),
            Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""),
        )
    }
}
