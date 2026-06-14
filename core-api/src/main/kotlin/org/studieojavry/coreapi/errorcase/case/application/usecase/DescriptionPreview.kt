package org.studieojavry.coreapi.errorcase.case.application.usecase

/**
 * 케이스 목록 미리보기용 description 변환.
 *
 *  - `@snippet(<markerId>)` → `[code]`
 *  - `@attach(<markerId>)` → `[file]`
 *  - 연속 공백/줄바꿈 → 단일 공백
 *  - max 120자 + 잘리면 끝에 `…` 추가
 *
 * 마커를 *제거* 하지 않고 짧은 placeholder 로 치환하는 이유: 미리보기에서 "이 케이스엔 코드/첨부가 있다"
 * 라는 신호를 잃지 않기 위해서. 정확한 코드/첨부는 상세에서.
 */
object DescriptionPreview {

    const val MAX_LENGTH = 120

    // markerId 는 영숫자/하이픈/언더스코어 — Attachment.embedToken / CodeSnippet.embedToken 패턴과 일치
    private val SNIPPET_MARKER = Regex("@snippet\\([A-Za-z0-9_-]+\\)")
    private val ATTACH_MARKER = Regex("@attach\\([A-Za-z0-9_-]+\\)")
    private val WHITESPACE = Regex("\\s+")

    fun of(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw
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
