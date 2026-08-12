package org.studieojavry.insightapi.activity.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.insightapi.activity.application.port.ActivityDailyRepositoryPort
import org.studieojavry.insightapi.activity.application.port.UserTimezonePort
import org.studieojavry.insightapi.activity.domain.ActivityType
import java.time.LocalDate
import java.time.ZoneId

/**
 * "My KPIs" — 사용자 활동 누적 통계 + 최근 7일 delta.
 *
 * 5 type 의 lifetime count 와 weekly count 를 `breakdown_json` 합산으로 산출.
 * 사용자 timezone 의 today 기준 7일 (오늘 포함) 을 weekly window 로 잡음.
 * resolved/raised 비율은 BE 에서 0.0~1.0 으로 계산해 응답.
 */
@Service
class GetMyKpisUseCase(
    private val dailyRepository: ActivityDailyRepositoryPort,
    private val userTimezone: UserTimezonePort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long): Result {
        val tz: ZoneId = userTimezone.fetch(userId)
        val today = LocalDate.now(tz)
        val weekStart = today.minusDays(6) // last 7 days inclusive

        val raised = metric(userId, ActivityType.CASE_CREATED, weekStart)
        val resolved = metric(userId, ActivityType.CASE_RESOLVED, weekStart)
        val steps = metric(userId, ActivityType.STEP_ADDED, weekStart)
        val solutions = metric(userId, ActivityType.SOLUTION_ADDED, weekStart)
        val published = metric(userId, ActivityType.CASE_PUBLISHED, weekStart)

        val resolutionRate = if (raised.total > 0)
            resolved.total.toDouble() / raised.total.toDouble()
        else 0.0
        val publishRate = if (resolved.total > 0)
            published.total.toDouble() / resolved.total.toDouble()
        else 0.0

        return Result(
            errorsRaised = raised,
            resolved = resolved,
            stepsAdded = steps,
            solutions = solutions,
            published = published,
            resolutionRate = resolutionRate,
            publishRate = publishRate,
        )
    }

    private fun metric(userId: Long, type: ActivityType, weekStart: LocalDate): KpiMetric =
        KpiMetric(
            total = dailyRepository.sumCountByType(userId, type),
            weeklyDelta = dailyRepository.sumCountByTypeSince(userId, type, weekStart),
        )

    data class KpiMetric(val total: Long, val weeklyDelta: Long)

    data class Result(
        val errorsRaised: KpiMetric,
        val resolved: KpiMetric,
        val stepsAdded: KpiMetric,
        val solutions: KpiMetric,
        val published: KpiMetric,
        val resolutionRate: Double,
        val publishRate: Double,
    )
}
