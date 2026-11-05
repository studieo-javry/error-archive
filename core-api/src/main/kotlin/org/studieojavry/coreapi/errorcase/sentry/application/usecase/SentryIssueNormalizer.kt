package org.studieojavry.coreapi.errorcase.sentry.application.usecase

import org.studieojavry.coreapi.errorcase.sentry.domain.model.vo.NormalizedSentryIssue
import tools.jackson.databind.JsonNode

/**
 * Sentry `issue` 웹훅 payload → [NormalizedSentryIssue].
 *
 * **화이트리스트 추출 = 마스킹**: id·title·culprit·type·level·permalink·project 만 뽑고, user·request·
 * headers·contexts·env·server_name 등 민감/동적 필드는 애초에 건드리지 않는다.
 * 처리 불가(resource 가 issue 아님 / 이슈 id·프로젝트 slug 없음) 면 null → 호출부가 SKIP.
 */
object SentryIssueNormalizer {

    fun normalize(resource: String?, root: JsonNode): NormalizedSentryIssue? {
        if (resource?.lowercase() != "issue") return null

        val issue = root.path("data").path("issue")
        val issueId = issue.path("id").asStringOrNull() ?: return null
        val project = issue.path("project")
        val projectSlug = project.path("slug").asStringOrNull() ?: return null

        val culprit = issue.path("culprit").asStringOrNull()
        val issueType = issue.path("metadata").path("type").asStringOrNull()

        return NormalizedSentryIssue(
            issueId = issueId,
            shortId = issue.path("shortId").asStringOrNull(),
            projectSlug = projectSlug,
            org = project.path("organization").path("slug").asStringOrNull(),
            title = issue.path("title").asStringOrNull() ?: "(제목 없음)",
            culprit = culprit,
            issueType = issueType,
            level = issue.path("level").asStringOrNull() ?: "error",
            permalink = issue.path("permalink").asStringOrNull(),
            fingerprintKey = fingerprintKey(issueType, culprit),
        )
    }

    /**
     * 과거 Solution 매칭용 정규화 키 — `type|culprit` 에서 환경마다 달라지는 동적값 제거.
     * 16진 주소·UUID·긴 숫자열을 placeholder 로 치환해 같은 "종류"의 에러가 같은 키가 되게 한다.
     */
    fun fingerprintKey(issueType: String?, culprit: String?): String {
        val raw = (listOfNotNull(issueType, culprit).joinToString("|")).ifBlank { "unknown" }
        return raw
            .lowercase()
            .replace(HEX_ADDR, "0xN")
            .replace(UUID, "uuid")
            .replace(LONG_DIGITS, "N")
            .replace(WHITESPACE, " ")
            .trim()
            .take(400)
    }

    private fun JsonNode.asStringOrNull(): String? =
        takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

    private val HEX_ADDR = Regex("0x[0-9a-f]+")
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val LONG_DIGITS = Regex("\\d{3,}")
    private val WHITESPACE = Regex("\\s+")
}
