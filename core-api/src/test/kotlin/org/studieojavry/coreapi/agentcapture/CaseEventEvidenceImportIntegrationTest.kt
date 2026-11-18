package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureEventRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptDetail
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptResult
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAttempt
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEvent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEventStatus
import org.studieojavry.coreapi.errorcase.case.application.usecase.DeleteErrorCaseUseCase
import org.studieojavry.coreapi.errorcase.eventevidence.application.port.CaseEventEvidenceRepositoryPort
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * (수집 근거 선택적 Import PR-2) selectedEventIds → CaseEventEvidence 스냅샷, 422 검증, 소유자 전용 조회, 삭제 cascade.
 * Testcontainers + validate(V30 정합 증명).
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
class CaseEventEvidenceImportIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var captures: AgentCaptureRepositoryPort
    @Autowired private lateinit var attempts: CaptureAttemptRepositoryPort
    @Autowired private lateinit var eventsPort: CaptureEventRepositoryPort
    @Autowired private lateinit var caseEventEvidence: CaseEventEvidenceRepositoryPort
    @Autowired private lateinit var deleteCase: DeleteErrorCaseUseCase
    private lateinit var mvc: MockMvc

    private val userId = 8800L
    private val otherUser = 8900L

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))
    private fun now() = Instant.now()

    /** READY_FOR_REVIEW 캡처 + attempt 1건. 반환: (uuid, captureId, connectionId). revision=1. */
    private fun seedReadyCapture(owner: Long): Triple<UUID, Long, UUID> {
        val uuid = UUID.randomUUID()
        val conn = UUID.randomUUID()
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
        val captureId = saved.id!!
        attempts.save(
            CaptureAttempt.create(
                captureId, "a1", 0, AttemptResult.SUCCEEDED, "fix",
                AttemptDetail(title = "Null-guard", action = "add ?: 0"), now(),
            ),
        )
        val c = captures.findByUuid(uuid)!!
        c.complete(now())
        captures.save(c)
        return Triple(uuid, captureId, conn)
    }

    private fun seedEvent(
        conn: UUID,
        owner: Long,
        clientEventId: String,
        status: CaptureEventStatus,
        captureId: Long?,
        payload: String = """{"tool":"Bash","cmd":"./gradlew test"}""",
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
                redactedPayload = payload,
                payloadHash = "h-$clientEventId",
                sessionId = "sess-1",
                status = status,
                captureId = captureId,
            ),
        )
    }

    private fun importBody(vararg eventIds: String): String {
        val evs = eventIds.joinToString(",") { "\"$it\"" }
        return """{"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedEventIds":[$evs]}"""
    }

    private fun doImport(uuid: UUID, user: Long, key: String, body: String) =
        mvc.perform(
            post("/api/v1/captures/$uuid/import").with(asUser(user))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun caseIdOf(result: String): Long =
        com.jayway.jsonpath.JsonPath.read<Int>(result, "$.caseId").toLong()

    @Test
    fun `선택한 LINKED 이벤트가 CaseEventEvidence 로 반영되고 소유자 조회에 노출`() {
        val (uuid, captureId, conn) = seedReadyCapture(userId)
        seedEvent(conn, userId, "e1", CaptureEventStatus.LINKED, captureId)
        seedEvent(conn, userId, "e2", CaptureEventStatus.LINKED, captureId) // 선택 안 함 → 반영 제외

        val res = doImport(uuid, userId, "imp-1", importBody("e1"))
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val caseId = caseIdOf(res)

        // 소유자 조회에 e1 만 노출, payload 스냅샷 보존
        mvc.perform(get("/api/v1/error-cases/$caseId/event-evidence").with(asUser(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].sourceClientEventId").value("e1"))
            .andExpect(jsonPath("$.items[0].eventType").value("PostToolUse:Bash"))
            .andExpect(jsonPath("$.items[0].redactedPayload.cmd").value("./gradlew test"))

        // 저장소에도 1건(e2 제외)
        assertEquals(1, caseEventEvidence.findAllByErrorCaseId(caseId).size)
    }

    @Test
    fun `연결 안 된(UNCLASSIFIED) 이벤트 선택은 422`() {
        val (uuid, _, conn) = seedReadyCapture(userId)
        seedEvent(conn, userId, "u1", CaptureEventStatus.UNCLASSIFIED, captureId = null)
        doImport(uuid, userId, "imp-2", importBody("u1"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("UNKNOWN_EVENT_REFERENCE"))
    }

    @Test
    fun `타 Capture 에 연결된 이벤트 선택은 422`() {
        val (uuid, _, conn) = seedReadyCapture(userId)
        val (_, otherCaptureId, _) = seedReadyCapture(userId)
        seedEvent(conn, userId, "x1", CaptureEventStatus.LINKED, captureId = otherCaptureId)
        doImport(uuid, userId, "imp-3", importBody("x1"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("UNKNOWN_EVENT_REFERENCE"))
    }

    @Test
    fun `event-evidence 조회 - 타인 403, 없는 Case 404`() {
        val (uuid, captureId, conn) = seedReadyCapture(userId)
        seedEvent(conn, userId, "e1", CaptureEventStatus.LINKED, captureId)
        val res = doImport(uuid, userId, "imp-4", importBody("e1"))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val caseId = caseIdOf(res)

        mvc.perform(get("/api/v1/error-cases/$caseId/event-evidence").with(asUser(otherUser)))
            .andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/error-cases/99999999/event-evidence").with(asUser(userId)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `Case 삭제 시 수집 근거도 정리된다`() {
        val (uuid, captureId, conn) = seedReadyCapture(userId)
        seedEvent(conn, userId, "e1", CaptureEventStatus.LINKED, captureId)
        val res = doImport(uuid, userId, "imp-5", importBody("e1"))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val caseId = caseIdOf(res)
        assertEquals(1, caseEventEvidence.findAllByErrorCaseId(caseId).size)

        deleteCase.invoke(caseId, userId)
        assertTrue(caseEventEvidence.findAllByErrorCaseId(caseId).isEmpty())
    }
}
