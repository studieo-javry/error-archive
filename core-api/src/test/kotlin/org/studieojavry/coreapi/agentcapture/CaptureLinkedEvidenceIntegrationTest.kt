package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import org.studieojavry.coreapi.agentcapture.application.port.CaptureEventRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSessionRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.usecase.AttachReviewEventsUseCase
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEvent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEventStatus
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotReviewableException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSession
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * (연결 수집 근거 조회 PR-1) 검증:
 *  - evidenceRefs 조회 왕복 복원(과거 하드코딩 []의 잠재 손상 제거)
 *  - GET /captures/{uuid}/events — 연결된(LINKED) 근거만·격리·keyset 페이지네이션
 *  - 조건부 원자 연결 — 교차-Capture 동시 연결 시 정확히 1건만 성공
 *  - 비-READY(Import 이후) 연결 거부(연결⇄반영 직렬화의 Import-선행 결과)
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
class CaptureLinkedEvidenceIntegrationTest {

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
    @Autowired private lateinit var attachReviewEvents: AttachReviewEventsUseCase
    private lateinit var mvc: MockMvc

    private val userId = 9500L
    private val otherUser = 9600L

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))
    private fun now() = Instant.now()

    /** 세션 저장 → id. clientSessionId 로 Capture 와 이벤트의 세션 범위를 잇는다. */
    private fun seedSession(conn: UUID, clientSessionId: String): Long =
        sessionsPort.save(
            CaptureSession.register(UUID.randomUUID(), conn, "claude-code", null, clientSessionId, now()),
        ).id!!

    private fun seedCapture(owner: Long, conn: UUID, sessionId: Long, seal: Boolean, import: Boolean = false): UUID {
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
        if (seal || import) {
            val c = captures.findByUuid(uuid)!!
            c.complete(now())
            if (import) c.markImported(123L, now())
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
        evidenceRefs: List<String> = emptyList(),
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
                evidenceRefs = evidenceRefs,
            ),
        )
    }

    private fun captureId(uuid: UUID): Long = captures.findByUuid(uuid)!!.id!!

    @Test
    fun `evidenceRefs 는 조회 시 저장값 그대로 복원된다`() {
        val conn = UUID.randomUUID()
        seedEvent(conn, userId, "ev-refs", "sess-1", evidenceRefs = listOf("artifact-1", "artifact-2"))

        val loaded = eventsPort.findByConnectionAndClientId(conn, "ev-refs")
        assertEquals(listOf("artifact-1", "artifact-2"), loaded?.evidenceRefs)
    }

    @Test
    fun `GET events - 이 Capture 의 LINKED 만 반환(미연결·타 Capture 제외)`() {
        val conn = UUID.randomUUID()
        val sid = seedSession(conn, "sess-1")
        val uuid = seedCapture(userId, conn, sid, seal = true)
        val cid = captureId(uuid)

        val otherSid = seedSession(conn, "sess-2")
        val otherUuid = seedCapture(userId, conn, otherSid, seal = true)
        val otherCid = captureId(otherUuid)

        seedEvent(conn, userId, "l1", "sess-1", status = CaptureEventStatus.LINKED, captureId = cid)
        seedEvent(conn, userId, "l2", "sess-1", status = CaptureEventStatus.LINKED, captureId = cid)
        seedEvent(conn, userId, "u1", "sess-1") // 미연결 → 제외
        seedEvent(conn, userId, "x1", "sess-2", status = CaptureEventStatus.LINKED, captureId = otherCid) // 타 Capture → 제외

        mvc.perform(get("/api/v1/captures/$uuid/events").with(asUser(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[*].clientEventId", org.hamcrest.Matchers.containsInAnyOrder("l1", "l2")))
            .andExpect(jsonPath("$.items[*].status", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.`is`("LINKED"))))
            .andExpect(jsonPath("$.hasNext").value(false))
    }

    @Test
    fun `GET events - keyset 페이지네이션`() {
        val conn = UUID.randomUUID()
        val sid = seedSession(conn, "sess-1")
        val uuid = seedCapture(userId, conn, sid, seal = true)
        val cid = captureId(uuid)
        seedEvent(conn, userId, "p1", "sess-1", status = CaptureEventStatus.LINKED, captureId = cid)
        seedEvent(conn, userId, "p2", "sess-1", status = CaptureEventStatus.LINKED, captureId = cid)

        // limit=1 → 1건 + hasNext=true + nextCursor
        val res = mvc.perform(get("/api/v1/captures/$uuid/events?limit=1").with(asUser(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty)
            .andReturn()
        val cursor = com.jayway.jsonpath.JsonPath.read<Int>(res.response.contentAsString, "$.nextCursor")

        // 다음 페이지 → 나머지 1건, 더 없음
        mvc.perform(get("/api/v1/captures/$uuid/events?limit=1&cursor=$cursor").with(asUser(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
    }

    @Test
    fun `GET events - 타인 소유 Capture 는 404`() {
        val conn = UUID.randomUUID()
        val sid = seedSession(conn, "sess-1")
        val uuid = seedCapture(userId, conn, sid, seal = true)
        mvc.perform(get("/api/v1/captures/$uuid/events").with(asUser(otherUser)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `조건부 원자 연결 - 교차 Capture 동시 연결은 정확히 1건만 성공`() {
        val conn = UUID.randomUUID()
        // 두 Capture 가 같은 clientSessionId 범위를 공유해, 하나의 이벤트를 둘 다 연결 시도할 수 있게 한다.
        val sidA = seedSession(conn, "sess-shared")
        val sidB = seedSession(conn, "sess-shared")
        val a = seedCapture(userId, conn, sidA, seal = true)
        val b = seedCapture(userId, conn, sidB, seal = true)
        val idA = captureId(a)
        val idB = captureId(b)
        seedEvent(conn, userId, "race", "sess-shared")

        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val taskA = Callable { barrier.await(); attachReviewEvents.invoke(a, userId, listOf("race")) }
            val taskB = Callable { barrier.await(); attachReviewEvents.invoke(b, userId, listOf("race")) }
            val rA = pool.submit(taskA)
            val rB = pool.submit(taskB)
            val resA = rA.get()
            val resB = rB.get()

            val totalLinked = resA.linked.size + resB.linked.size
            assertEquals(1, totalLinked, "정확히 한 Capture 만 이벤트를 연결해야 한다")
            val winnerCaptureId = eventsPort.findByConnectionAndClientId(conn, "race")!!.captureId
            assertTrue(winnerCaptureId == idA || winnerCaptureId == idB)
            assertEquals(CaptureEventStatus.LINKED, eventsPort.findByConnectionAndClientId(conn, "race")!!.status)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `비-READY(Import 이후) Capture 연결은 거부된다`() {
        val conn = UUID.randomUUID()
        val sid = seedSession(conn, "sess-1")
        val uuid = seedCapture(userId, conn, sid, seal = true, import = true) // IMPORTED
        seedEvent(conn, userId, "e1", "sess-1")

        assertThrows<CaptureNotReviewableException> {
            attachReviewEvents.invoke(uuid, userId, listOf("e1"))
        }
    }
}
