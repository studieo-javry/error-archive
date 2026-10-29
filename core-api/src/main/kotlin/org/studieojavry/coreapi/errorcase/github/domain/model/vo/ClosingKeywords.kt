package org.studieojavry.coreapi.errorcase.github.domain.model.vo

/**
 * PR 제목·본문의 "닫는 키워드" (`Fixes #142`, `closes acme/api#7`, `Resolved: #3`).
 *
 * 두 군데에 쓴다.
 *  - 웹훅 사전 필터: 키워드가 없으면 GitHub API 를 부르지 않는다.
 *  - **기본 브랜치가 아닌 PR**: GitHub 는 base 가 기본 브랜치일 때만 키워드를 공식 연결(closingIssuesReferences)로 인정한다.
 *    `alpha`·`develop` 으로 가는 PR 은 여기서 직접 판정해 장부에 KEYWORD 출처로 넣는다(P1 설계 Q4).
 * 단순 언급(`#142 관련`)은 닫는 키워드가 아니므로 잡지 않는다.
 */
object ClosingKeywords {
    private val PATTERN = Regex(
        """(?i)(?<![\w-])(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)(?:\s*:\s*|\s+)(?:([A-Za-z0-9-]+/[A-Za-z0-9._-]+))?#(\d{1,9})(?!\w)""",
    )

    data class Ref(val fullName: String, val number: Int)

    fun contains(text: String?): Boolean = text != null && PATTERN.containsMatchIn(text)

    /** [defaultRepo] 는 `#N` 처럼 저장소 없이 쓴 경우의 저장소(= PR 의 저장소). */
    fun parse(text: String?, defaultRepo: String): List<Ref> =
        if (text.isNullOrBlank()) emptyList()
        else PATTERN.findAll(text).map { m ->
            Ref(m.groupValues[1].ifEmpty { defaultRepo }, m.groupValues[2].toInt())
        }.distinctBy { it.fullName.lowercase() to it.number }.toList()
}
