package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSolutionRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptDetail
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptResult
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAttempt
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSolutionCandidate
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import org.studieojavry.coreapi.agentcapture.domain.model.SolutionContent
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ContentSource
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * Agent Capture(P0) PR4 — 검토 반영(import) 트랜잭션. Testcontainers + validate(V21 정합 증명).
 * 봉인된 초안을 사용자 경로(/api/v1/captures)로 반영 → 신규 PRIVATE·OPEN 케이스 + Step/Solution 매핑.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(AgentCaptureImportIntegrationTest.Config::class)
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
])
class AgentCaptureImportIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    /** 발행 이벤트를 기록해 origin/type 을 검증(@Primary 로 Noop 대체). */
    class RecordingPublisher : ActivityEventPublisherPort {
        val events = mutableListOf<ActivityEventPublisherPort.ActivityEvent>()
        override fun publish(event: ActivityEventPublisherPort.ActivityEvent) { events += event }
    }

    @TestConfiguration
    class Config {
        @Bean @Primary
        fun recordingPublisher() = RecordingPublisher()
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var captures: AgentCaptureRepositoryPort
    @Autowired private lateinit var attempts: CaptureAttemptRepositoryPort
    @Autowired private lateinit var solutions: CaptureSolutionRepositoryPort
    @Autowired private lateinit var errorCases: ErrorCaseRepositoryPort
    @Autowired private lateinit var steps: StepRepositoryPort
    @Autowired private lateinit var caseSolutions: SolutionRepositoryPort
    @Autowired private lateinit var publisher: RecordingPublisher
    private lateinit var mvc: MockMvc

    private val userId = 7001L

    @BeforeEach
    fun setUp() {
        publisher.events.clear()
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    private fun now() = Instant.now()

    /** OPEN 초안 생성 + Attempt/Solution 시드. seal=true 면 READY_FOR_REVIEW 로 봉인. */
    private fun seedCapture(owner: Long, seal: Boolean): Pair<UUID, Long> {
        val uuid = UUID.randomUUID()
        val saved = captures.save(
            AgentCapture.start(
                captureUuid = uuid,
                ownerUserId = owner,
                originatingConnectionId = UUID.randomUUID(),
                title = "NullPointer on checkout",
                project = "checkout-svc",
                content = CaptureContent(
                    symptom = "NPE at CartService.total()",
                    observedFacts = listOf("stacktrace: NPE line 42"),
                    hypotheses = listOf("cart item price null"),
                ),
                now = now(),
            )
        )
        val captureId = saved.id!!
        attempts.save(CaptureAttempt.create(captureId, "a1", 0, AttemptResult.FAILED, "repro",
            AttemptDetail(title = "Reproduce the NPE", action = "run failing test"), now()))
        attempts.save(CaptureAttempt.create(captureId, "a2", 1, AttemptResult.SUCCEEDED, "fix",
            AttemptDetail(title = "Null-guard the price", action = "add ?: 0", insight = "price was nullable"), now()))
        solutions.save(CaptureSolutionCandidate.create(captureId, "s1", "Guard null price",
            SolutionContent(clientAttemptIds = listOf("a2")), now()))
        if (seal) {
            val c = captures.findByUuid(uuid)!!
            c.complete(now())
            captures.save(c)
        }
        return uuid to captureId
    }

    @Test
    fun `봉인된 초안 반영 - 신규 PRIVATE·OPEN 케이스 + Step·Solution 매핑 + IMPORTED`() {
        val (uuid, captureId) = seedCapture(userId, seal = true)

        mvc.perform(post("/api/v1/captures/$uuid/import").with(asUser(userId)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.alreadyImported").value(false))
            .andExpect(jsonPath("$.caseId").isNumber)

        // 초안은 IMPORTED + 케이스 백링크
        val reloaded = captures.findByUuid(uuid)!!
        assertEquals(CaptureStatus.IMPORTED, reloaded.status)
        val caseId = reloaded.importedCaseId!!

        // 케이스: 본인 소유, PRIVATE, OPEN(자동 RESOLVED 금지), 출처 표식
        val case = errorCases.findById(caseId)!!
        assertEquals(userId, case.ownerUserId)
        assertEquals(Visibility.PRIVATE, case.visibility)
        assertEquals(ErrorCaseStatus.OPEN, case.status)
        assertEquals(ContentSource.AGENT_IMPORT, case.source)
        assertEquals(userId, case.reviewedByUserId)
        assertEquals(captureId, case.sourceCaptureId)
        assertNotNull(case.description)

        // Step: 2개(순서 보존), 결과→상태 매핑(FAILED→FAILED, SUCCEEDED→RESOLVED)
        val createdSteps = steps.findAllByErrorCaseId(caseId).sortedBy { it.orderIndex }
        assertEquals(2, createdSteps.size)
        assertEquals(StepStatus.FAILED, createdSteps[0].status)
        assertEquals(StepStatus.RESOLVED, createdSteps[1].status)

        // Solution: 1개, a2 의 step 참조
        val createdSolutions = caseSolutions.findAllByErrorCaseId(caseId)
        assertEquals(1, createdSolutions.size)
        assertEquals(listOf(createdSteps[1].id), createdSolutions[0].stepIds)

        // 활동 이벤트: CASE_CREATED(origin=AGENT_IMPORT, 게이트됨) + CAPTURE_REVIEWED(origin=null, 집계)
        assertTrue(publisher.events.any {
            it.type == ActivityEventPublisherPort.Type.CASE_CREATED &&
                it.origin == ActivityEventPublisherPort.Origin.AGENT_IMPORT
        })
        assertTrue(publisher.events.any {
            it.type == ActivityEventPublisherPort.Type.CAPTURE_REVIEWED && it.origin == null
        })
    }

    @Test
    fun `반영 멱등 - 이미 IMPORTED 면 같은 케이스를 200 으로 반환`() {
        val (uuid, _) = seedCapture(userId, seal = true)

        val first = mvc.perform(post("/api/v1/captures/$uuid/import").with(asUser(userId)))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val caseId1 = Regex("\"caseId\":(\\d+)").find(first)!!.groupValues[1]

        mvc.perform(post("/api/v1/captures/$uuid/import").with(asUser(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.alreadyImported").value(true))
            .andExpect(jsonPath("$.caseId").value(caseId1.toInt()))
    }

    @Test
    fun `봉인되지 않은(OPEN) 초안 반영은 409`() {
        val (uuid, _) = seedCapture(userId, seal = false)

        mvc.perform(post("/api/v1/captures/$uuid/import").with(asUser(userId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CAPTURE_NOT_REVIEWABLE"))
    }

    @Test
    fun `폐기 - READY 초안을 DISCARDED 로, 반영된 초안은 폐기 불가(409)`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        mvc.perform(post("/api/v1/captures/$uuid/discard").with(asUser(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DISCARDED"))

        // 이미 반영된 초안은 폐기 불가
        val (imported, _) = seedCapture(userId, seal = true)
        mvc.perform(post("/api/v1/captures/$imported/import").with(asUser(userId))).andExpect(status().isCreated)
        mvc.perform(post("/api/v1/captures/$imported/discard").with(asUser(userId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CAPTURE_ALREADY_IMPORTED"))
    }

    @Test
    fun `타인 소유 초안 반영은 404`() {
        val (uuid, _) = seedCapture(owner = 9999L, seal = true)
        mvc.perform(post("/api/v1/captures/$uuid/import").with(asUser(userId)))
            .andExpect(status().isNotFound)
    }
}
