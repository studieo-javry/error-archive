package org.studieojavry.insightapi.activity.domain

import java.time.LocalDate

/**
 * (오늘부터 거꾸로) 연속 활동 일수 + 1년치 최장 연속 일수.
 *
 * 입력은 `(date → eventCount > 0)` 의 정렬된 시퀀스 (오름차순). 비활동 일이 누락되어 있어도
 * `referenceToday` 로 끊김을 정확히 판정한다.
 */
data class Streak(
    val current: Int,
    val longest: Int,
    val currentStartDate: LocalDate?,
) {
    companion object {
        /**
         * @param activeDates 활동이 있었던 LocalDate 들 (오름차순 정렬되어 있다고 가정)
         * @param today 사용자 timezone 기준 오늘. current streak 시작점 판정.
         */
        fun compute(activeDates: List<LocalDate>, today: LocalDate): Streak {
            if (activeDates.isEmpty()) return Streak(0, 0, null)

            // longest — 연속된 날짜 run 의 최대 길이
            var longest = 1
            var run = 1
            for (i in 1 until activeDates.size) {
                if (activeDates[i] == activeDates[i - 1].plusDays(1)) {
                    run += 1
                    if (run > longest) longest = run
                } else if (activeDates[i] != activeDates[i - 1]) {
                    run = 1
                }
            }

            // current — 오늘부터 거꾸로 연속된 길이. 오늘 비활동이면 어제부터 허용 (GitHub 동일 정책)
            val last = activeDates.last()
            val gap = today.toEpochDay() - last.toEpochDay()
            if (gap > 1) return Streak(0, longest, null)

            var current = 1
            var cursor = last
            for (i in activeDates.size - 2 downTo 0) {
                val d = activeDates[i]
                if (d == cursor.minusDays(1)) { current += 1; cursor = d }
                else if (d != cursor) break
            }
            return Streak(current, longest, cursor)
        }
    }
}
