package org.studieojavry.insightapi.activity.infrastructure.metrics

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 제품 참여 지표 **수동 재집계** — internal-auth 호출자만(internal 경로는 gateway 가 외부로 라우팅하지 않아 클러스터 내부만 도달).
 *
 * 평소 집계는 [ProductEngagementMetrics.recompute] 의 @Scheduled(일 1회, 새벽 3:10 KST)지만,
 * **배포 직후·데모·검증** 때 일 배치를 기다리지 않고 즉시 `ea_*` gauge 를 채우려면 이 엔드포인트로
 * [ProductEngagementMetrics.doRecompute] 를 강제 호출한다(@SchedulerLock 없이 동기 실행).
 * ProductEngagementMetrics 와 **동일 조건부(enabled)** 로만 등록되어, 지표 비활성 시 함께 꺼진다.
 */
@Tag(name = "product-metrics-admin", description = "insight-api 제품 참여 지표 운영 — internal-auth 호출자만.")
@RestController
@RequestMapping("/internal/admin/product-metrics")
@ConditionalOnProperty(prefix = "insight.product-metrics", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ProductMetricsAdminController(
    private val metrics: ProductEngagementMetrics,
) {

    @Operation(
        summary = "제품 참여 지표 즉시 재집계",
        description = "원장을 다시 집계해 ea_* gauge 를 갱신한다. 일 1회 배치(03:10 KST)를 기다리지 않고 " +
            "배포 직후·데모·검증 시 사용. 동기 실행이라 완료 후 200 과 소요시간을 반환한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재집계 완료"),
        ApiResponse(responseCode = "401", description = "internal-auth 누락", content = [Content()]),
    )
    @PostMapping("/recompute")
    fun recompute(): RecomputeResponse {
        val t0 = System.nanoTime()
        metrics.doRecompute()
        return RecomputeResponse(recomputedAt = Instant.now(), elapsedMs = (System.nanoTime() - t0) / 1_000_000)
    }
}

data class RecomputeResponse(
    val recomputedAt: Instant,
    val elapsedMs: Long,
)
