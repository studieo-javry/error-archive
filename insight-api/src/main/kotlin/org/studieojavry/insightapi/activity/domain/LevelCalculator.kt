package org.studieojavry.insightapi.activity.domain

/**
 * 잔디 5단계 색 결정.
 *
 * `mode = RELATIVE` — 그 사용자 *비영(非零)* 점수의 25/50/75 분위로 t2~t4. t1 = 1 (절대 컷).
 * `mode = ABSOLUTE` — 운영팀 고정 컷.
 *
 * 활동 없는 사용자(scores 비어있음)도 결정적 결과 — t1~t4 = 1.
 */
data class Thresholds(val l1: Int, val l2: Int, val l3: Int, val l4: Int) {
    fun levelOf(score: Int): Int = when {
        score < l1 -> 0
        score >= l4 -> 4
        score >= l3 -> 3
        score >= l2 -> 2
        else -> 1
    }
}

enum class GrassMode { RELATIVE, ABSOLUTE }

object LevelCalculator {
    fun compute(scores: List<Int>, mode: GrassMode): Thresholds {
        val sorted = scores.filter { it > 0 }.sorted()
        if (mode == GrassMode.ABSOLUTE || sorted.isEmpty()) return ABSOLUTE
        fun q(p: Double): Int = sorted[((sorted.size - 1) * p).toInt()]
        return Thresholds(l1 = 1, l2 = q(0.25), l3 = q(0.50), l4 = q(0.75))
    }

    val ABSOLUTE = Thresholds(l1 = 1, l2 = 5, l3 = 12, l4 = 24)
}
