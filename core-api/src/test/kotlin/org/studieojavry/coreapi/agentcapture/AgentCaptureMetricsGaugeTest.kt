package org.studieojavry.coreapi.agentcapture

import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.infrastructure.metrics.AgentCaptureMetrics
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * ARC Prometheus 게이지(`agent.capture.count{status}`)가 DB 상태별 건수를 반영하는지 검증.
 * 전역 게이지라 fresh 컨테이너에 알려진 건수만 시드해 확인한다.
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
class AgentCaptureMetricsGaugeTest {

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var captures: AgentCaptureRepositoryPort
    @Autowired private lateinit var meterRegistry: MeterRegistry

    private fun seed(to: String) {
        val uuid = UUID.randomUUID()
        captures.save(
            AgentCapture.start(
                captureUuid = uuid, ownerUserId = 123L, originatingConnectionId = UUID.randomUUID(),
                title = "bug", project = null, content = CaptureContent.EMPTY, now = Instant.now(),
            )
        )
        if (to == "OPEN") return
        val c = captures.findByUuid(uuid)!!; c.complete(Instant.now()); captures.save(c)
        if (to == "DISCARDED") { val d = captures.findByUuid(uuid)!!; d.discard(Instant.now()); captures.save(d) }
        if (to == "IMPORTED") { val d = captures.findByUuid(uuid)!!; d.markImported(1L, Instant.now()); captures.save(d) }
    }

    private fun gauge(status: String): Double =
        meterRegistry.find(AgentCaptureMetrics.METRIC_COUNT).tag("status", status).gauge()!!.value()

    @Test
    fun `agent_capture_count 게이지가 상태별 DB 건수를 반영한다`() {
        seed("OPEN"); seed("OPEN")
        seed("READY")
        seed("IMPORTED")
        seed("DISCARDED"); seed("DISCARDED"); seed("DISCARDED")

        assertEquals(2.0, gauge("OPEN"))
        assertEquals(1.0, gauge("READY_FOR_REVIEW"))
        assertEquals(1.0, gauge("IMPORTED"))
        assertEquals(3.0, gauge("DISCARDED"))
    }
}
