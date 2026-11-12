package org.studieojavry.coreapi.errorcase.shared.application.usecase

/**
 * 본문(Case description / Step body)에 박히는 배치 마커 토큰 처리 공용 유틸.
 *  - `@attach(markerId)` — 첨부 배치
 *  - `@snippet(markerId)` — 코드 스니펫 배치
 *
 * markerId 는 영숫자/하이픈/언더스코어(Attachment.embedToken / CodeSnippet.embedToken 과 동일 패턴).
 * "어디에 배치됐는가"(인라인 여부)·"어떤 마커를 참조하는가"(소속 검증) 판단을 한 규칙으로 통일한다.
 */
object MarkerTokens {
    private val ATTACH = Regex("@attach\\(([A-Za-z0-9_-]+)\\)")
    private val SNIPPET = Regex("@snippet\\(([A-Za-z0-9_-]+)\\)")

    /** text 안의 `@attach(x)` markerId 집합. */
    fun attachIds(text: String?): Set<String> =
        text?.let { ATTACH.findAll(it).map { m -> m.groupValues[1] }.toSet() } ?: emptySet()

    /** text 안의 `@snippet(x)` markerId 집합. */
    fun snippetIds(text: String?): Set<String> =
        text?.let { SNIPPET.findAll(it).map { m -> m.groupValues[1] }.toSet() } ?: emptySet()
}
