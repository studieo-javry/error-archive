package org.studieojavry.insightapi.activity.infrastructure.metrics

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.studieojavry.insightapi.activity.application.port.UserSignupPort
import org.studieojavry.insightapi.activity.config.ProductMetricsProperties
import org.studieojavry.insightapi.activity.infrastructure.ActivityEventJpaRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 제품 참여(engagement) 지표 — 사용자가 에러 케이스를 얼마나·어떻게 등록·재방문하는지.
 *
 * **왜 배치인가**: 이 지표들은 사용자별·시간기반(가입→첫 케이스, 연속 작성 간격, 유저당 케이스 수 …)이라
 * Prometheus 라벨에 userId 를 넣으면 카디널리티가 폭발한다. 그래서 하루 1회 배치가 원장
 * (`insight_activity_event`)을 집계해 **분포(p50/p90)·비율·카운트**를 소수의 저카디널리티 gauge 로만 노출한다.
 * (실시간 "흐름"은 core-api 쓰기경로 카운터 `ea_activity_published_total` 가 담당 — 역할 분리.)
 *
 * 노출 gauge:
 *  - `ea_active_creators{window=1d|7d|28d}`         기간 내 1건+ 작성한 distinct 유저 수
 *  - `ea_cases_per_user{stat=p50|p90|avg|max}`      유저당 누적 케이스 수 분포
 *  - `ea_time_to_first_case_hours{stat=p50|p90}`    가입→첫 케이스 지연(시간)
 *  - `ea_inter_case_interval_hours{stat=p50|p90}`   연속 케이스 간 간격(시간)
 *  - `ea_cases_by_origin{origin=USER|AGENT_IMPORT}` 최근 창 생성 수(수동 vs 에이전트)
 *  - `ea_cohort_signups` / `ea_activated_users` / `ea_activation_rate`  (가입 코호트 퍼널)
 *  - `ea_one_and_done_ratio` / `ea_retention_w1_ratio`                  (이탈·습관화)
 *  - `ea_cases_public_ratio` / `ea_cases_created_window` / `ea_cases_resolved_window`
 *  - `ea_cohort_size`(=표본 N) 은 작은 N 에서의 분포 노이즈를 읽을 때 분모로 함께 본다.
 *
 * ShedLock 으로 다중 인스턴스 중 leader 1개만 계산(중복 집계 방지). 실패는 swallow + warn — 지표 집계가
 * 도메인/다른 지표를 멈추지 않게.
 */
@Component
@ConditionalOnProperty(
    prefix = "insight.product-metrics",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ProductEngagementMetrics(
    private val events: ActivityEventJpaRepository,
    private val signups: UserSignupPort,
    private val props: ProductMetricsProperties,
    registry: MeterRegistry,
) {
    private val log = KotlinLogging.logger {}

    // 스칼라 holder — gauge 는 한 번 등록하고 배치가 값만 갱신한다.
    @Volatile private var cohortSignups = 0.0
    @Volatile private var activatedUsers = 0.0
    @Volatile private var activationRate = 0.0
    @Volatile private var publicRatio = 0.0
    @Volatile private var oneAndDoneRatio = 0.0
    @Volatile private var retentionW1Ratio = 0.0
    @Volatile private var casesCreatedWindow = 0.0
    @Volatile private var casesResolvedWindow = 0.0

    private val gActiveCreators = MultiGauge.builder("ea.active.creators")
        .description("기간 내 1건 이상 케이스를 쓴 distinct 유저 수").register(registry)
    private val gCasesPerUser = MultiGauge.builder("ea.cases.per.user")
        .description("유저당 누적 케이스 수 분포").register(registry)
    private val gTtfc = MultiGauge.builder("ea.time.to.first.case.hours")
        .baseUnit("hours").description("가입→첫 케이스 지연 분포").register(registry)
    private val gInterval = MultiGauge.builder("ea.inter.case.interval.hours")
        .baseUnit("hours").description("연속 케이스 간 간격 분포").register(registry)
    private val gByOrigin = MultiGauge.builder("ea.cases.by.origin")
        .description("최근 창 케이스 생성 수(출처별: 수동 USER vs 에이전트 AGENT_IMPORT)").register(registry)

    init {
        Gauge.builder("ea.cohort.signups", this) { it.cohortSignups }
            .description("최근 가입 코호트 크기(표본 N)").register(registry)
        Gauge.builder("ea.activated.users", this) { it.activatedUsers }
            .description("코호트 중 첫 케이스를 쓴 유저 수").register(registry)
        Gauge.builder("ea.activation.rate", this) { it.activationRate }
            .description("활성화율 = 첫 케이스 작성 유저 / 코호트 (0~1)").register(registry)
        Gauge.builder("ea.cases.public.ratio", this) { it.publicRatio }
            .description("최근 창 공개(PUBLIC) 케이스 비율 (0~1)").register(registry)
        Gauge.builder("ea.one.and.done.ratio", this) { it.oneAndDoneRatio }
            .description("케이스 1개만 쓰고 만 유저 비율 (0~1)").register(registry)
        Gauge.builder("ea.retention.w1.ratio", this) { it.retentionW1Ratio }
            .description("첫 케이스 후 7일 내 2번째 작성 비율 (0~1)").register(registry)
        Gauge.builder("ea.cases.created.window", this) { it.casesCreatedWindow }
            .description("최근 창 생성된 케이스 수").register(registry)
        Gauge.builder("ea.cases.resolved.window", this) { it.casesResolvedWindow }
            .description("최근 창 해결(CASE_RESOLVED) 활동 수").register(registry)
    }

    @Scheduled(cron = "\${insight.product-metrics.cron:0 10 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "insight-product-metrics", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    fun recompute() {
        try {
            doRecompute()
        } catch (e: Exception) {
            log.warn(e) { "product engagement metrics recompute failed — keeping previous values" }
        }
    }

    internal fun doRecompute() {
        val now = Instant.now()
        val activityWindowStart = now.minus(Duration.ofDays(props.activityWindowDays))

        // 1) CASE_CREATED(사람) 원장 → 유저별 정렬된 시각.
        val byUser: Map<Long, List<Instant>> = events.caseCreatedUserTimes()
            .map { r -> (r[0] as Number).toLong() to toInstant(r[1]) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.sorted() }

        // active creators by window
        gActiveCreators.register(
            listOf(1L to "1d", 7L to "7d", 28L to "28d").map { (d, label) ->
                val since = now.minus(Duration.ofDays(d))
                val n = byUser.count { (_, times) -> times.any { !it.isBefore(since) } }
                MultiGauge.Row.of(Tags.of("window", label), n.toDouble())
            },
            true,
        )

        // cases per user distribution
        val counts = byUser.values.map { it.size.toDouble() }.sorted()
        gCasesPerUser.register(
            listOf(
                statRow("p50", percentile(counts, 0.5)),
                statRow("p90", percentile(counts, 0.9)),
                statRow("avg", if (counts.isEmpty()) 0.0 else counts.average()),
                statRow("max", counts.maxOrNull() ?: 0.0),
            ),
            true,
        )

        // one-and-done + W1 retention (습관화)
        val creators = byUser.filter { it.value.isNotEmpty() }
        oneAndDoneRatio = ratio(creators.count { it.value.size == 1 }, creators.size)
        retentionW1Ratio = ratio(
            creators.count { it.value.size >= 2 && Duration.between(it.value[0], it.value[1]) <= Duration.ofDays(7) },
            creators.size,
        )

        // inter-case intervals (hours)
        val gaps = byUser.values
            .flatMap { times -> times.zipWithNext { a, b -> Duration.between(a, b).toMinutes() / 60.0 } }
            .sorted()
        gInterval.register(
            listOf(statRow("p50", percentile(gaps, 0.5)), statRow("p90", percentile(gaps, 0.9))),
            true,
        )

        // activation: 가입 코호트 ⋈ 첫 케이스
        val cohortStart = now.minus(Duration.ofDays(props.cohortLookbackDays))
        val cohort = signups.recentSignups(cohortStart, props.signupFetchLimit)
        val latencies = ArrayList<Double>()
        var activated = 0
        for (s in cohort) {
            val first = byUser[s.userId]?.firstOrNull()
            if (first != null && !first.isBefore(s.createdAt)) {
                activated++
                latencies.add(Duration.between(s.createdAt, first).toMinutes() / 60.0)
            }
        }
        cohortSignups = cohort.size.toDouble()
        activatedUsers = activated.toDouble()
        activationRate = ratio(activated, cohort.size)
        latencies.sort()
        gTtfc.register(
            listOf(statRow("p50", percentile(latencies, 0.5)), statRow("p90", percentile(latencies, 0.9))),
            true,
        )

        // source mix (origin) — 최근 창
        gByOrigin.register(
            events.caseCountsByOriginSince(activityWindowStart).map { r ->
                MultiGauge.Row.of(Tags.of("origin", r[0].toString()), (r[1] as Number).toDouble())
            },
            true,
        )

        // 공개율 + 생성/해결 수 — 최근 창
        val pc = events.casePublicCountsSince(activityWindowStart).firstOrNull()
        val pub = pc?.let { (it[0] as Number).toDouble() } ?: 0.0
        val tot = pc?.let { (it[1] as Number).toDouble() } ?: 0.0
        publicRatio = if (tot == 0.0) 0.0 else pub / tot
        casesCreatedWindow = tot
        casesResolvedWindow = events.countByTypeSince("CASE_RESOLVED", activityWindowStart).toDouble()

        log.info {
            "product engagement metrics updated: cohort=$cohortSignups activated=$activatedUsers " +
                "rate=${"%.3f".format(activationRate)} creators(≥1)=${creators.size} " +
                "oneAndDone=${"%.3f".format(oneAndDoneRatio)} w1Retention=${"%.3f".format(retentionW1Ratio)}"
        }
    }

    private fun statRow(stat: String, v: Double) = MultiGauge.Row.of(Tags.of("stat", stat), v)

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    /** 선형보간 percentile (nearest-rank 보다 작은 N 에서 안정적). 빈 목록은 0. */
    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        if (lo == hi) return sorted[lo]
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    /** 네이티브 timestamptz 가 드라이버/하이버네이트 버전에 따라 Timestamp/OffsetDateTime 등으로 와도 흡수. */
    private fun toInstant(v: Any): Instant = when (v) {
        is Instant -> v
        is java.sql.Timestamp -> v.toInstant()
        is OffsetDateTime -> v.toInstant()
        is LocalDateTime -> v.toInstant(ZoneOffset.UTC)
        else -> Instant.parse(v.toString())
    }
}
