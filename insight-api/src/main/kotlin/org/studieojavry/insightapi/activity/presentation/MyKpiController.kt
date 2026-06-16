package org.studieojavry.insightapi.activity.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.insightapi.activity.application.usecase.GetMyKpisUseCase

@Tag(name = "my-kpis", description = "내 활동 누적 KPI — total + 최근 7일 delta")
@RestController
@RequestMapping("/api/v1")
class MyKpiController(
    private val getMyKpisUseCase: GetMyKpisUseCase,
) {
    @Operation(
        summary = "내 활동 KPI",
        description = """
            5 type 의 누적 카운트 + 최근 7일 delta 반환.
            - errorsRaised: CASE_CREATED
            - resolved: CASE_RESOLVED  (resolutionRate = resolved.total / errorsRaised.total)
            - stepsAdded: STEP_ADDED
            - solutions: SOLUTION_ADDED
            - published: CASE_PUBLISHED  (publishRate = published.total / resolved.total)

            weekly window = 사용자 timezone 기준 today 포함 최근 7일.
            모든 값은 `insight_activity_daily.breakdown_json` 으로부터 jsonb 합산.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/users/me/kpis")
    fun myKpis(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
    ): MyKpisResponse {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        val r = getMyKpisUseCase.invoke(uid)
        return MyKpisResponse(
            errorsRaised = KpiMetricDto(r.errorsRaised.total, r.errorsRaised.weeklyDelta),
            resolved = KpiMetricDto(r.resolved.total, r.resolved.weeklyDelta),
            stepsAdded = KpiMetricDto(r.stepsAdded.total, r.stepsAdded.weeklyDelta),
            solutions = KpiMetricDto(r.solutions.total, r.solutions.weeklyDelta),
            published = KpiMetricDto(r.published.total, r.published.weeklyDelta),
            resolutionRate = r.resolutionRate,
            publishRate = r.publishRate,
        )
    }
}

@Schema(description = "내 KPI 응답")
data class MyKpisResponse(
    @field:Schema(description = "누적 errors raised (CASE_CREATED)")
    val errorsRaised: KpiMetricDto,
    @field:Schema(description = "누적 resolved (CASE_RESOLVED)")
    val resolved: KpiMetricDto,
    @field:Schema(description = "누적 steps added (STEP_ADDED)")
    val stepsAdded: KpiMetricDto,
    @field:Schema(description = "누적 solutions (SOLUTION_ADDED)")
    val solutions: KpiMetricDto,
    @field:Schema(description = "누적 published (CASE_PUBLISHED)")
    val published: KpiMetricDto,
    @field:Schema(description = "resolved / errorsRaised — 0.0~1.0. 분모 0 일 때 0.0", example = "0.68")
    val resolutionRate: Double,
    @field:Schema(description = "published / resolved — 0.0~1.0. 분모 0 일 때 0.0", example = "0.25")
    val publishRate: Double,
)

@Schema(description = "단일 KPI 지표 — 누적 + 주간 delta")
data class KpiMetricDto(
    @field:Schema(description = "전 기간 누적 count", example = "47")
    val total: Long,
    @field:Schema(description = "최근 7일 (today 포함) count", example = "3")
    val weeklyDelta: Long,
)
