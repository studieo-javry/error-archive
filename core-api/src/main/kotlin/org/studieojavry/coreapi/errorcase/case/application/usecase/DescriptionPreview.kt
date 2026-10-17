package org.studieojavry.coreapi.errorcase.case.application.usecase

/**
 * 케이스 목록 미리보기용 description 변환.
 *
 * 리스트 카드 미리보기는 **마크다운을 렌더하지 않는 플레인 텍스트 발췌**다(실무 표준). 따라서 본문의
 * 마크다운 문법(헤더 `#`, 굵게 `**`, 인라인/펜스 코드, 목록 `-`/`1.`, 인용 `>`)을 **제거**해 날것 기호가
 * 노출되지 않게 한다. 임베드 마커만은 신호를 살려 placeholder 로 남긴다:
 *  - 코드펜스 블록 → 제거(미리보기 노이즈)
 *  - `**굵게**`/`*기울임*`/`` `코드` `` → 내용만 남김
 *  - 라인 선두 `#`/`-`/`1.`/`>` 마커 → 제거(텍스트는 유지)
 *  - `@snippet(<markerId>)` → `[code]`, `@attach(<markerId>)` → `[file]`
 *  - 연속 공백/줄바꿈 → 단일 공백, max 120자 + 잘리면 `…`
 */
object DescriptionPreview {

    const val MAX_LENGTH = 120

    // markerId 는 영숫자/하이픈/언더스코어 — Attachment.embedToken / CodeSnippet.embedToken 패턴과 일치
    private val SNIPPET_MARKER = Regex("@snippet\\([A-Za-z0-9_-]+\\)")
    private val ATTACH_MARKER = Regex("@attach\\([A-Za-z0-9_-]+\\)")
    private val WHITESPACE = Regex("\\s+")

    // 마크다운 스트립용
    private val FENCE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`([^`]+)`")
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")
    private val ITALIC = Regex("\\*([^*]+)\\*")
    private val LINE_MARKER = Regex("(?m)^\\s{0,3}(?:#{1,6}\\s+|[-*+]\\s+|\\d+\\.\\s+|>\\s?)")

    fun of(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw
        // 마크다운 문법 스트립(렌더 전 기호가 미리보기에 새지 않도록)
        s = FENCE.replace(s, " ")
        s = INLINE_CODE.replace(s, "$1")
        s = BOLD.replace(s, "$1")
        s = ITALIC.replace(s, "$1")
        s = LINE_MARKER.replace(s, "")
        // 임베드 마커는 신호를 살려 placeholder 로
        s = SNIPPET_MARKER.replace(s, "[code]")
        s = ATTACH_MARKER.replace(s, "[file]")
        s = WHITESPACE.replace(s, " ").trim()
        if (s.isEmpty()) return null
        if (s.length <= MAX_LENGTH) return s

        // take(120) 의 끝에 닫히지 않은 '[' 가 있으면 깨진 placeholder — 그 위치부터 끝까지 잘라낸다.
        // 예) "...117자 [co" (lastOpen=118, lastClose=-1) → "...117자" + "…"
        //     "...113자 [code] 이후" (lastOpen<lastClose) → 그대로
        val truncated = s.take(MAX_LENGTH)
        val lastOpen = truncated.lastIndexOf('[')
        val lastClose = truncated.lastIndexOf(']')
        val cleaned = if (lastOpen > lastClose) truncated.substring(0, lastOpen) else truncated
        return cleaned.trimEnd() + "…"
    }
}
