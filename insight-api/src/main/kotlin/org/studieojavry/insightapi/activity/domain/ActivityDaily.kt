package org.studieojavry.insightapi.activity.domain

import java.time.Instant
import java.time.LocalDate

/**
 * 사용자 × (사용자 timezone 기준) 일자별 활동 집계.
 *
 * `breakdown` = `{activityType : count}` JSON. 잔디 조회 시 한 row 가 한 cell.
 */
class ActivityDaily private constructor(
    val userId: Long,
    val activityDate: LocalDate,
    val eventCount: Int,
    val scoreSum: Int,
    val breakdown: Map<String, Int>,
    val updatedAt: Instant,
) {
    companion object {
        fun rehydrate(
            userId: Long, activityDate: LocalDate, eventCount: Int, scoreSum: Int,
            breakdown: Map<String, Int>, updatedAt: Instant,
        ) = ActivityDaily(userId, activityDate, eventCount, scoreSum, breakdown, updatedAt)
    }
}
