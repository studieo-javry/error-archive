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
}
