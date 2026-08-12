package org.studieojavry.insightapi.activity.application.port

import org.studieojavry.insightapi.activity.domain.ActivityDaily
import org.studieojavry.insightapi.activity.domain.ActivityType
import java.time.LocalDate

interface ActivityDailyRepositoryPort {
    /**
     * (userId, date) 에 1건 누적. 없으면 INSERT, 있으면 count/score/breakdown 증가.
     * native ON CONFLICT 으로 atomic 보장.
     */
    fun increment(userId: Long, date: LocalDate, type: ActivityType, score: Int)

    fun findInRange(userId: Long, from: LocalDate, to: LocalDate): List<ActivityDaily>

    /**
     * 특정 type 의 누적 count — 사용자 전 기간. KPI 누적값 계산용.
     */
    fun sumCountByType(userId: Long, type: ActivityType): Long

    /**
     * 특정 type 의 누적 count — activity_date >= since 만. weekly delta 등 계산용.
     */
    fun sumCountByTypeSince(userId: Long, type: ActivityType, since: LocalDate): Long
}
