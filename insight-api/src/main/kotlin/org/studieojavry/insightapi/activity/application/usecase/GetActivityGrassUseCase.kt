package org.studieojavry.insightapi.activity.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.insightapi.activity.application.port.ActivityDailyRepositoryPort
import org.studieojavry.insightapi.activity.application.port.UserTimezonePort
import org.studieojavry.insightapi.activity.domain.GrassMode
import org.studieojavry.insightapi.activity.domain.LevelCalculator
import org.studieojavry.insightapi.activity.domain.Streak
import org.studieojavry.insightapi.activity.domain.Thresholds
import java.time.LocalDate
import java.time.ZoneId

/**
 * 모든 잔디는 공개. private 토글이 사용자 통제권에 비해 운영 복잡도가 커서 제거 (v0.2).
 * 필요해지면 Visibility aggregate + check 를 재도입.
 */
@Service
class GetActivityGrassUseCase(
    private val dailyRepository: ActivityDailyRepositoryPort,
    private val userTimezone: UserTimezonePort,
) {
    @Transactional(readOnly = true)
    fun invoke(
        targetUserId: Long,
        from: LocalDate?,
        to: LocalDate?,
        mode: GrassMode,
    ): Result {
        // range — default 최근 1년 (target user timezone 기준 today 끝)
        val tz: ZoneId = userTimezone.fetch(targetUserId)
        val today = LocalDate.now(tz)
        val rangeTo = to ?: today
        val rangeFrom = from ?: rangeTo.minusDays(364)
        // 3) daily fetch — 활동 있는 날만
        val rows = dailyRepository.findInRange(targetUserId, rangeFrom, rangeTo)
        val byDate = rows.associateBy { it.activityDate }

        // 4) days fill — 비활동 일도 포함
        val days = generateSequence(rangeFrom) { d -> if (d < rangeTo) d.plusDays(1) else null }
            .map { date ->
                val row = byDate[date]
                Day(
                    date = date,
                    count = row?.eventCount ?: 0,
                    score = row?.scoreSum ?: 0,
                    breakdown = row?.breakdown ?: emptyMap(),
                )
            }
            .toList()

        // 5) level 계산 + 적용
        val thresholds = LevelCalculator.compute(days.map { it.score }, mode)
        val withLevel = days.map { it.copy(level = thresholds.levelOf(it.score)) }

        // 6) streak, totals, best
        val activeDates = withLevel.filter { it.count > 0 }.map { it.date }
        val streak = Streak.compute(activeDates, today)
        val totalEvents = withLevel.sumOf { it.count }
        val totalScore = withLevel.sumOf { it.score }
        val activeDays = activeDates.size
        val best = withLevel.maxByOrNull { it.score }?.takeIf { it.score > 0 }

        return Result(
            rangeFrom = rangeFrom, rangeTo = rangeTo, timezone = tz.id,
            days = withLevel, streak = streak,
            totalEvents = totalEvents, totalScore = totalScore, activeDays = activeDays,
            bestDay = best, mode = mode, thresholds = thresholds,
        )
    }

    data class Day(
        val date: LocalDate,
        val count: Int,
        val score: Int,
        val breakdown: Map<String, Int>,
        val level: Int = 0,
    )

    data class Result(
        val rangeFrom: LocalDate,
        val rangeTo: LocalDate,
        val timezone: String,
        val days: List<Day>,
        val streak: Streak,
        val totalEvents: Int,
        val totalScore: Int,
        val activeDays: Int,
        val bestDay: Day?,
        val mode: GrassMode,
        val thresholds: Thresholds,
    )
}
