package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureEventRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSessionRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEvent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEventStatus
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSession
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * (PR-5 E) 리뷰 백스톱 통합테스트 — 봉인 후 뒤늦게 도착한 **같은 세션의 미연결 이벤트**를
 * 사용자 경로(/api/v1/captures/{uuid}/orphan-events)로 조회·연결. Testcontainers + validate.
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
class CaptureOrphanEventsIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var captures: AgentCaptureRepositoryPort
    @Autowired private lateinit var sessionsPort: CaptureSessionRepositoryPort
    @Autowired private lateinit var eventsPort: CaptureEventRepositoryPort
    private lateinit var mvc: MockMvc

    private val userId = 9100L
    private val otherUser = 9200L

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))
    private fun now() = Instant.now()

    /** 세션 저장 → id. */
    private fun seedSession(conn: UUID, clientSessionId: String): Long =
        sessionsPort.save(
            CaptureSession.register(UUID.randomUUID(), conn, "claude-code", null, clientSessionId, now()),
        ).id!!

    /** 봉인 여부 지정 캡처(세션 연결까지). */
    private fun seedCapture(owner: Long, conn: UUID, sessionId: Long, seal: Boolean): UUID {
        val uuid = UUID.randomUUID()
        val saved = captures.save(
            AgentCapture.start(
                captureUuid = uuid,
                ownerUserId = owner,
                originatingConnectionId = conn,
                title = "NPE on checkout",
                project = "checkout",
                content = CaptureContent(symptom = "NPE", observedFacts = listOf("x"), hypotheses = listOf("y")),
                now = now(),
            ),
        )
        sessionsPort.link(sessionId, saved.id!!)
        if (seal) {
            val c = captures.findByUuid(uuid)!!
            c.complete(now())
            captures.save(c)
        }
        return uuid
    }

    private fun seedEvent(
        conn: UUID,
        owner: Long,
        clientEventId: String,
        sessionId: String?,
        status: CaptureEventStatus = CaptureEventStatus.UNCLASSIFIED,
        captureId: Long? = null,
    ) {
        eventsPort.save(
            CaptureEvent(
                connectionId = conn,
                ownerUserId = owner,
                clientEventId = clientEventId,
                eventType = "PostToolUse:Bash",
                localSequence = 1,
                observedAt = now(),
                receivedAt = now(),
                redactedPayload = "{}",
                payloadHash = "h-$clientEventId",
                sessionId = sessionId,
                status = status,
                captureId = captureId,
            ),
        )
    }

    @Test
    fun `orphan-events - 같은 세션 미연결만 반환(타 세션·타 연결·이미연결 제외)`() {
        val conn = UUID.randomUUID()
        val sid = seedSession(conn, "sess-1")
        val uuid = seedCapture(userId, conn, sid, seal = true)

        seedEvent(conn, userId, "o1", "sess-1") // 고아(대상)
        seedEvent(conn, userId, "o2", "sess-1") // 고아(대상)
        seedEvent(conn, userId, "x-other-session", "sess-2") // 타 세션 → 제외
        seedEvent(UUID.randomUUID(), userId, "x-other-conn", "sess-1") // 타 연결 → 제외
        seedEvent(conn, userId, "x-linked", "sess-1", status = CaptureEventStatus.LINKED, captureId = 1L) // 이미 연결 → 제외

        mvc.perform(get("/api/v1/captures/$uuid/orphan-events").with(asUser(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[*].clientEventId", org.hamcrest.Matchers.containsInAnyOrder("o1", "o2")))
    }

    @Test
    fun `orphan-events link - 선택분 연결 후 조회에서 사라짐, 재연결 멱등`() {
        val conn = UUID.randomUUID()
        val sid = seedSession(conn, "sess-1")
        val uuid = seedCapture(userId, conn, sid, seal = true)
        seedEvent(conn, userId, "o1", "sess-1")
        seedEvent(conn, userId, "o2", "sess-1")

        mvc.perform(
            post("/api/v1/captures/$uuid/orphan-events/link").with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"clientEventIds":["o1"]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.linked", org.hamcrest.Matchers.contains("o1")))

        // o1 은 연결됐으니 조회에서 빠지고 o2 만 남는다
        mvc.perform(get("/api/v1/captures/$uuid/orphan-events").with(asUser(userId)))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].clientEventId").value("o2"))

        // 같은 Capture 로 재연결은 멱등(linked)
        mvc.perform(
            post("/api/v1/captures/$uuid/orphan-events/link").with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"clientEventIds":["o1"]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.linked", org.hamcrest.Matchers.contains("o1")))
    }

    @Test
    fun `orphan-events link - READY_FOR_REVIEW 아니면 409`() {
        val conn = UUID.randomUUID()
        val sid = seedSession(conn, "sess-1")
        val uuid = seedCapture(userId, conn, sid, seal = false) // OPEN
        seedEvent(conn, userId, "o1", "sess-1")

        mvc.perform(
            post("/api/v1/captures/$uuid/orphan-events/link").with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"clientEventIds":["o1"]}"""),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `orphan-events - 타인 소유 Capture 는 404`() {
        val conn = UUID.randomUUID()
        val sid = seedSession(conn, "sess-1")
        val uuid = seedCapture(userId, conn, sid, seal = true)

        mvc.perform(get("/api/v1/captures/$uuid/orphan-events").with(asUser(otherUser)))
            .andExpect(status().isNotFound)
    }
}
