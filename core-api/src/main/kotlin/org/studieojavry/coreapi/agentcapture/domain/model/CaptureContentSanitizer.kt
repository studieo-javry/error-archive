package org.studieojavry.coreapi.agentcapture.domain.model

/**
 * Capture 서사 필드에서 민감정보를 **저장 전에** 제거한다(이슈 #144 §2).
 *
 * 제거 대상:
 *  - URL 에 포함된 자격증명: `scheme://user:pass@host` → `scheme://host`
 *  - 사용자 홈 절대 경로: `/Users/<name>/…`, `/home/<name>/…`, `C:\Users\<name>\…` → `~/…`
 *  - 이메일 주소: `foo@bar.com` → `[email]`
 *
 * 구조는 보존하고 문자열만 치환한다(식별자 필드 — clientId·commitSha·evidenceRefs 등 — 은 건드리지 않는다).
 * "완전한 비식별"을 보증하지는 않는다. 명백한 누출 패턴을 걷어내는 best-effort 방어선이다.
 */
object CaptureContentSanitizer {

    // scheme://userinfo@host → scheme://host  (user 또는 user:pass 모두)
    private val URL_CREDENTIALS = Regex("""([a-zA-Z][a-zA-Z0-9+.\-]*://)[^/@\s]+@""")

    // 유닉스/윈도우 홈 경로의 사용자명 세그먼트까지 포함해 ~ 로 축약
    private val UNIX_HOME = Regex("""(?:/Users/|/home/)[^/\s"']+""")
    private val WINDOWS_HOME = Regex("""[A-Za-z]:\\Users\\[^\\\s"']+""")

    // 이메일 (URL 자격증명 제거 후 적용)
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    fun sanitize(text: String): String =
        text
            .replace(URL_CREDENTIALS) { it.groupValues[1] }
            .let { UNIX_HOME.replace(it, "~") }
            .let { WINDOWS_HOME.replace(it, "~") }
            .let { EMAIL.replace(it, "[email]") }

    private fun orNull(text: String?): String? = text?.let { sanitize(it) }
    private fun each(texts: List<String>): List<String> = texts.map { sanitize(it) }

    fun sanitize(content: CaptureContent): CaptureContent =
        content.copy(
            symptom = orNull(content.symptom),
            observedFacts = each(content.observedFacts),
            hypotheses = each(content.hypotheses),
            reproduction = content.reproduction?.copy(steps = each(content.reproduction.steps)),
            environment = content.environment?.copy(diffRef = orNull(content.environment.diffRef)),
            unknowns = each(content.unknowns),
        )

    fun sanitize(detail: AttemptDetail): AttemptDetail =
        detail.copy(
            title = sanitize(detail.title),
            hypothesis = orNull(detail.hypothesis),
            action = orNull(detail.action),
            insight = orNull(detail.insight),
            nextStep = orNull(detail.nextStep),
        )

    fun sanitize(content: SolutionContent): SolutionContent =
        content.copy(
            description = orNull(content.description),
            verification = content.verification?.copy(
                command = orNull(content.verification.command),
                evidence = orNull(content.verification.evidence),
            ),
        )
}
