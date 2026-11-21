package org.studieojavry.insightapi.activity

import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.studieojavry.insightapi.activity.application.port.UserSignupPort
import org.studieojavry.insightapi.activity.application.port.UserTimezonePort
import org.studieojavry.insightapi.activity.application.usecase.IngestActivityEventUseCase
import org.studieojavry.insightapi.activity.domain.ActivityOrigin
import org.studieojavry.insightapi.activity.infrastructure.metrics.ProductEngagementMetrics
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * 제품 참여 지표 배치의 **실환경 검증** — 실 Postgres(Testcontainers) + Flyway(V1~) + ddl-auto=validate.
 * 원장(`insight_activity_event`)을 ingest use case 로 시딩한 뒤 [ProductEngagementMetrics.doRecompute] 를
 * 직접 호출해, 네이티브 집계 SQL(FILTER·meta_json->>·따옴표 alias·timestamptz→Instant)과 분포/비율 계산이
 * 실 DB 에서 정확히 도는지 gauge 값으로 확정한다. (Kafka 불요 — use case 직접 호출.)
 *
 * 결정론적 시나리오:
 *  - A(1001): CASE_CREATED now-26h, now-2h(PUBLIC)  → 2건, 간격 24h
 *  - B(1002): CASE_CREATED now-10d                   → 1건 (one-and-done)
 *  - C(1003): CASE_CREATED now-1h, origin=AGENT_IMPORT → 사람집계 제외(소스믹스엔 포함)
 *  - CASE_RESOLVED now-3h (해결 활동 1건)
 *  - 가입 코호트(mock): A(now-30h), B(now-11d), D(1004, now-5h·케이스없음)
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
])
class ProductEngagementMetricsIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var ingest: IngestActivityEventUseCase
    @Autowired private lateinit var metrics: ProductEngagementMetrics
    @Autowired private lateinit var registry: MeterRegistry
    @MockitoBean private lateinit var userTimezone: UserTimezonePort
    @MockitoBean private lateinit var userSignup: UserSignupPort

    private val now = Instant.now()

    @BeforeEach
    fun setUp() {
        // 잔디 일자 계산용 timezone — UTC 고정(ingest 가 occurredAt.atZone(tz) 호출)
        Mockito.`when`(userTimezone.fetch(anyLong())).thenReturn(ZoneOffset.UTC)

        // 가입 코호트 stub — A/B 는 케이스 있음(활성화), D 는 없음(미활성화)
        Mockito.`when`(userSignup.recentSignups(anyInstant(), anyInt())).thenReturn(
            listOf(
                UserSignupPort.Signup(1001L, now.minus(Duration.ofHours(30))),
                UserSignupPort.Signup(1002L, now.minus(Duration.ofDays(11))),
                UserSignupPort.Signup(1004L, now.minus(Duration.ofHours(5))),
            )
        )

        seed(1001L, "CASE_CREATED", "case:a1", now.minus(Duration.ofHours(26)), ActivityOrigin.USER, null)
        seed(1001L, "CASE_CREATED", "case:a2", now.minus(Duration.ofHours(2)), ActivityOrigin.USER, """{"visibility":"PUBLIC"}""")
        seed(1002L, "CASE_CREATED", "case:b1", now.minus(Duration.ofDays(10)), ActivityOrigin.USER, null)
        seed(1003L, "CASE_CREATED", "case:c1", now.minus(Duration.ofHours(1)), ActivityOrigin.AGENT_IMPORT, null)
        seed(1001L, "CASE_RESOLVED", "resolve:a1", now.minus(Duration.ofHours(3)), ActivityOrigin.USER, null)
    }

    @Test
    fun `배치가 원장에서 참여 지표를 실 Postgres 로 집계한다`() {
        metrics.doRecompute()

        // 활성 작성자(사람): 1d=A만, 7d=A만, 28d=A+B
        assertEquals(1.0, gauge("ea.active.creators", "window", "1d"))
        assertEquals(1.0, gauge("ea.active.creators", "window", "7d"))
        assertEquals(2.0, gauge("ea.active.creators", "window", "28d"))

        // 유저당 케이스 수 분포: counts=[1(B),2(A)]
        assertEquals(1.5, gauge("ea.cases.per.user", "stat", "p50"), 1e-6)
        assertEquals(1.9, gauge("ea.cases.per.user", "stat", "p90"), 1e-6)
        assertEquals(1.5, gauge("ea.cases.per.user", "stat", "avg"), 1e-6)
        assertEquals(2.0, gauge("ea.cases.per.user", "stat", "max"), 1e-6)

        // 연속 케이스 간격: A 만 간격 보유 = 24h
        assertEquals(24.0, gauge("ea.inter.case.interval.hours", "stat", "p50"), 1e-6)
        assertEquals(24.0, gauge("ea.inter.case.interval.hours", "stat", "p90"), 1e-6)

        // 이탈·습관화: creators={A(2),B(1)} → one-and-done=1/2, W1 리텐션(A 간격 24h<=7d)=1/2
        assertEquals(0.5, gauge("ea.one.and.done.ratio"), 1e-6)
        assertEquals(0.5, gauge("ea.retention.w1.ratio"), 1e-6)

        // 활성화: 코호트 3(A,B,D), 활성화 2(A,B) → rate 2/3. 지연 latencies=[4h(A),24h(B)]
        assertEquals(3.0, gauge("ea.cohort.signups"))
        assertEquals(2.0, gauge("ea.activated.users"))
        assertEquals(2.0 / 3.0, gauge("ea.activation.rate"), 1e-6)
        assertEquals(14.0, gauge("ea.time.to.first.case.hours", "stat", "p50"), 1e-6)
        assertEquals(22.0, gauge("ea.time.to.first.case.hours", "stat", "p90"), 1e-6)

        // 소스믹스(28d): USER=A2+B1=3, AGENT_IMPORT=C1=1
        assertEquals(3.0, gauge("ea.cases.by.origin", "origin", "USER"))
        assertEquals(1.0, gauge("ea.cases.by.origin", "origin", "AGENT_IMPORT"))

        // 공개율(28d): 전체 CASE_CREATED=4(A2+B1+C1), PUBLIC=1(A2) → 0.25, 생성=4, 해결=1
        assertEquals(0.25, gauge("ea.cases.public.ratio"), 1e-6)
        assertEquals(4.0, gauge("ea.cases.created.window"))
        assertEquals(1.0, gauge("ea.cases.resolved.window"))
    }

    private fun seed(userId: Long, type: String, key: String, at: Instant, origin: ActivityOrigin, metaJson: String?) =
        ingest.invoke(
            IngestActivityEventUseCase.Input(
                userId = userId,
                typeCode = type,
                occurredAt = at,
                score = 0,
                idempotencyKey = key,
                metaJson = metaJson,
                origin = origin,
            )
        )

    private fun gauge(name: String, vararg tags: String): Double =
        registry.get(name).tags(*tags).gauge().value()

    /** Kotlin + Mockito any() 널 회피: 매처를 등록하고 non-null 더미 반환. */
    private fun anyInstant(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH
}
