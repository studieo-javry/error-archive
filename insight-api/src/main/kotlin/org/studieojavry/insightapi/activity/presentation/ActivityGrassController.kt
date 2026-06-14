package org.studieojavry.insightapi.activity.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.insightapi.activity.application.usecase.GetActivityGrassUseCase
import org.studieojavry.insightapi.activity.domain.GrassMode
import org.studieojavry.insightapi.activity.domain.Streak
import org.studieojavry.insightapi.activity.domain.Thresholds
import java.time.LocalDate

@Tag(name = "activity-grass", description = "사용자의 1년 활동 잔디 — Debug Pulse")
@RestController
@RequestMapping("/api/v1")
class ActivityGrassController(
    private val getGrassUseCase: GetActivityGrassUseCase,
) {
    @Operation(
        summary = "내 활동 잔디",
        description = """
            최근 1년(default) 의 일별 활동 카운트 + 점수 + level. `from/to` 로 기간 좁힐 수 있음.
            `mode=relative`(default) — 자기 분포 기반 / `mode=absolute` — 운영 고정 컷.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/users/me/activity-grass")
    fun myGrass(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false, defaultValue = "relative") mode: String,
    ): ActivityGrassResponse {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        return invoke(uid, from, to, mode)
    }

    @Operation(summary = "공개 사용자 잔디", description = "모든 잔디는 공개. 인증된 사용자라면 누구나 조회 가능.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/users/{userId}/activity-grass")
    fun publicGrass(
        @Parameter(hidden = true) @AuthenticationPrincipal viewerId: Long?,
        @PathVariable userId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false, defaultValue = "relative") mode: String,
    ): ActivityGrassResponse {
        viewerId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        return invoke(userId, from, to, mode)
    }

    private fun invoke(targetId: Long, from: LocalDate?, to: LocalDate?, mode: String): ActivityGrassResponse {
        val grassMode = runCatching { GrassMode.valueOf(mode.uppercase()) }.getOrDefault(GrassMode.RELATIVE)
        val r = getGrassUseCase.invoke(targetId, from, to, grassMode)
        return ActivityGrassResponse(
            rangeFrom = r.rangeFrom, rangeTo = r.rangeTo, timezone = r.timezone,
            days = r.days.map { DayDto(it.date, it.count, it.score, it.level, it.breakdown) },
            streak = StreakDto(r.streak.current, r.streak.longest, r.streak.currentStartDate),
            totals = TotalsDto(events = r.totalEvents, score = r.totalScore, activeDays = r.activeDays),
            bestDay = r.bestDay?.let { BestDayDto(it.date, it.score) },
            thresholds = ThresholdsDto(r.mode.name.lowercase(), r.thresholds),
        )
    }
}

@Schema(description = "활동 잔디 응답")
data class ActivityGrassResponse(
    val rangeFrom: LocalDate,
    val rangeTo: LocalDate,
    val timezone: String,
    val days: List<DayDto>,
    val streak: StreakDto,
    val totals: TotalsDto,
    val bestDay: BestDayDto?,
    val thresholds: ThresholdsDto,
)

data class DayDto(
    val date: LocalDate,
    val count: Int,
    val score: Int,
    val level: Int,
    val breakdown: Map<String, Int>,
)

data class StreakDto(val current: Int, val longest: Int, val currentStartDate: LocalDate?) {
    constructor(s: Streak) : this(s.current, s.longest, s.currentStartDate)
}

data class TotalsDto(val events: Int, val score: Int, val activeDays: Int)
data class BestDayDto(val date: LocalDate, val score: Int)
data class ThresholdsDto(val mode: String, val l1: Int, val l2: Int, val l3: Int, val l4: Int) {
    constructor(mode: String, t: Thresholds) : this(mode, t.l1, t.l2, t.l3, t.l4)
}
