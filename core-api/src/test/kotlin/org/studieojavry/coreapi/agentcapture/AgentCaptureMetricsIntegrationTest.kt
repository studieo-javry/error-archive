package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * 자동기록 계측 — GET /api/v1/captures/metrics(user JWT). 상태 퍼널(created/open/readyForReview/imported/
 * discarded)과 파생 비율(discardRate=오생성, adoptionRate=채택)을 검증. 상태별로 직접 시드.
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
class AgentCaptureMetricsIntegrationTest {

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var captures: AgentCaptureRepositoryPort
    private lateinit var mvc: MockMvc

    private val userId = 9500L

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    private fun seed(owner: Long, to: String) {
        val uuid = UUID.randomUUID()
        captures.save(
            AgentCapture.start(
                captureUuid = uuid, ownerUserId = owner, originatingConnectionId = UUID.randomUUID(),
                title = "bug", project = null, content = CaptureContent.EMPTY, now = Instant.now(),
            )
        )
        if (to == "OPEN") return
        val c1 = captures.findByUuid(uuid)!!; c1.complete(Instant.now()); captures.save(c1)
        when (to) {
            "IMPORTED" -> { val c = captures.findByUuid(uuid)!!; c.markImported(777L, Instant.now()); captures.save(c) }
            "DISCARDED" -> { val c = captures.findByUuid(uuid)!!; c.discard(Instant.now()); captures.save(c) }
            // "READY" → complete 까지만
        }
    }

    @Test
    fun `퍼널 집계와 오생성·채택 비율`() {
        seed(userId, "OPEN")
        seed(userId, "READY")
        seed(userId, "IMPORTED")
        seed(userId, "DISCARDED")
        seed(userId, "DISCARDED")
        // 타 사용자 것은 집계에 섞이지 않음
        seed(8888L, "IMPORTED")

        mvc.perform(get("/api/v1/captures/metrics").with(asUser(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(5))
            .andExpect(jsonPath("$.open").value(1))
            .andExpect(jsonPath("$.readyForReview").value(1))
            .andExpect(jsonPath("$.imported").value(1))
            .andExpect(jsonPath("$.discarded").value(2))
            .andExpect(jsonPath("$.reviewed").value(3))
            // discardRate = 2/3, adoptionRate = 1/3
            .andExpect(jsonPath("$.discardRate").value(2.0 / 3.0))
            .andExpect(jsonPath("$.adoptionRate").value(1.0 / 3.0))
    }

    @Test
    fun `검토완료 0 이면 비율은 null`() {
        seed(userId + 1, "OPEN")
        mvc.perform(get("/api/v1/captures/metrics").with(asUser(userId + 1)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(1))
            .andExpect(jsonPath("$.reviewed").value(0))
            .andExpect(jsonPath("$.discardRate").doesNotExist())
            .andExpect(jsonPath("$.adoptionRate").doesNotExist())
    }

    @Test
    fun `windowDays 는 1~365 로 클램프된다`() {
        mvc.perform(get("/api/v1/captures/metrics").param("windowDays", "99999").with(asUser(userId + 2)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.windowDays").value(365))
    }
}
