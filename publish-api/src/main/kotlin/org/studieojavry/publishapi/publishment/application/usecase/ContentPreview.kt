package org.studieojavry.publishapi.publishment.application.usecase

/**
 * 발행물 목록 카드용 **본문 미리보기** 생성.
 *
 * 목록 응답은 content(ContentSnapshot) 를 빼고 주므로, 저자 요약(summary)이 없을 때
 * 카드에 보여줄 미리보기가 없다. 이 유틸이 스냅샷의 `description` 본문에서 미리보기를 만든다.
 *  - 인라인 마커는 정보성 자리표시자로 치환: `@snippet(x)` → `[code]`, `@attach(x)` → `[file]`
 *    (core-api 의 description preview 규칙과 통일)
 *  - 공백 정리 후 [MAX] 자로 절단(초과 시 말줄임)
 *  - description 이 비어있으면 null (미리보기 없음)
 */
object ContentPreview {
    private val SNIPPET_MARKER = Regex("@snippet\\([A-Za-z0-9_-]+\\)")
    private val ATTACH_MARKER = Regex("@attach\\([A-Za-z0-9_-]+\\)")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX = 160

    fun of(description: String?): String? {
        val raw = description?.takeIf { it.isNotBlank() } ?: return null
        val replaced = raw
            .replace(SNIPPET_MARKER, "[code]")
            .replace(ATTACH_MARKER, "[file]")
        val oneLine = replaced.replace(WHITESPACE, " ").trim()
        if (oneLine.isBlank()) return null
        return if (oneLine.length > MAX) oneLine.take(MAX - 1).trimEnd() + "…" else oneLine
    }
}
