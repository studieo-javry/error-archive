package org.studieojavry.insightapi.activity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.studieojavry.insightapi.activity.application.port.ActivityDailyRepositoryPort
import org.studieojavry.insightapi.activity.application.port.UserTimezonePort
import org.studieojavry.insightapi.activity.application.usecase.IngestActivityEventUseCase
import org.studieojavry.insightapi.activity.domain.ActivityOrigin
import org.studieojavry.insightapi.activity.domain.ActivityType
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.ZoneOffset

/**
 * Agent Capture(P0) PR4 — origin 게이트. AGENT_IMPORT 는 daily 집계(잔디/KPI) 제외, USER·미지정은 집계.
 * 네이티브 upsert(ON CONFLICT ... RETURNING)·V3 마이그레이션을 쓰므로 실 Postgres(Testcontainers)로 검증
 * (ddl-auto=validate 가 origin 컬럼 ↔ 엔티티 정합을 증명). use case 를 직접 호출(Kafka 불요).
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
class ActivityOriginGateTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var ingest: IngestActivityEventUseCase
    @Autowired private lateinit var daily: ActivityDailyRepositoryPort
    @MockitoBean private lateinit var userTimezone: UserTimezonePort

    @BeforeEach
    fun setUp() {
        Mockito.`when`(userTimezone.fetch(anyLong())).thenReturn(ZoneOffset.UTC)
    }

    private fun ingest(userId: Long, type: String, key: String, origin: ActivityOrigin) =
        ingest.invoke(
            IngestActivityEventUseCase.Input(
                userId = userId,
                typeCode = type,
                occurredAt = Instant.parse("2026-11-09T10:00:00Z"),
                score = 0,
                idempotencyKey = key,
                metaJson = null,
                origin = origin,
            )
        )

    @Test
    fun `AGENT_IMPORT 는 daily 집계 제외, 일반·CAPTURE_REVIEWED 는 집계된다`() {
        val u = 9001L

        // 에이전트 반영으로 발행된 CASE_CREATED → daily 에서 제외
        ingest(u, "CASE_CREATED", "case:ai", ActivityOrigin.AGENT_IMPORT)
        // 사람이 직접 만든 CASE_CREATED → 집계
        ingest(u, "CASE_CREATED", "case:user", ActivityOrigin.USER)
        // 검토 기여(CAPTURE_REVIEWED, USER) → weight 1 집계
        ingest(u, "CAPTURE_REVIEWED", "capture-reviewed:1", ActivityOrigin.USER)

        // CASE_CREATED: agent-import 1건 제외 → 1(일반만)
        assertEquals(1L, daily.sumCountByType(u, ActivityType.CASE_CREATED))
        // CAPTURE_REVIEWED: 집계됨 → weight 1
        assertEquals(1L, daily.sumCountByType(u, ActivityType.CAPTURE_REVIEWED))
    }

    @Test
    fun `origin 미지정(null 폴백)은 USER 로 집계된다`() {
        val u = 9002L
        // Input.origin 기본값 = USER (발행 측이 origin 안 보낸 기존 메시지와 동일)
        ingest.invoke(
            IngestActivityEventUseCase.Input(
                userId = u,
                typeCode = "STEP_ADDED",
                occurredAt = Instant.parse("2026-11-09T10:00:00Z"),
                score = 0,
                idempotencyKey = "step:1",
                metaJson = null,
            )
        )
        assertEquals(1L, daily.sumCountByType(u, ActivityType.STEP_ADDED))
    }
}
