package org.studieojavry.publishapi.publishment.application.usecase

/**
 * 스텝 소요시간(분)의 사람용 포맷 — **HtmlRenderer / MarkdownRenderer 공통** 단일 규칙.
 *
 * 과거엔 렌더러마다 제각각이라(웹은 분을 통째로 버리고, MD 는 `.trimEnd('0',...)` 꼼수로 "30 min"→"3"
 * 처럼 망가뜨림 + 서로 다름) 버그였다. 여기 한 곳으로 통일해 시+분을 보존하고 웹=MD 를 보장한다.
 *
 *  - < 1h  : `45m`
 *  - < 24h : `2h` (정각) / `2h 30m`
 *  - >= 24h: `1d` / `1d 6h`
 */
object DurationFormat {
    fun humanize(minutes: Int): String {
        if (minutes < 60) return "${minutes}m"
        if (minutes < 60 * 24) {
            val h = minutes / 60
            val m = minutes % 60
            return if (m == 0) "${h}h" else "${h}h ${m}m"
        }
        val d = minutes / 1440
        val h = (minutes % 1440) / 60
        return if (h == 0) "${d}d" else "${d}d ${h}h"
    }
}
