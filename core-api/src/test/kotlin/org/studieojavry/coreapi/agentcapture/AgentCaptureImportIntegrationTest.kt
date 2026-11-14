package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import org.studieojavry.coreapi.agentcapture.application.port.CaptureArtifactRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSolutionRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.ProjectBindingRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBinding
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptDetail
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptResult
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureArtifact
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAttempt
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSolutionCandidate
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import org.studieojavry.coreapi.agentcapture.domain.model.SolutionContent
import org.studieojavry.coreapi.agentcapture.domain.model.Verification
import org.studieojavry.coreapi.agentcapture.domain.model.VerificationResult
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
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

    /**
     * 발행 이벤트를 기록해 origin/type 을 검증(@Primary 로 Noop 대체).
     * failNext=true 면 publish 에서 예외를 던져 "이벤트 저장(아웃박스) 실패" 를 주입한다.
     */
    class RecordingPublisher : ActivityEventPublisherPort {
        val events = mutableListOf<ActivityEventPublisherPort.ActivityEvent>()
        @Volatile var failNext = false
        override fun publish(event: ActivityEventPublisherPort.ActivityEvent) {
            if (failNext) throw IllegalStateException("simulated activity/outbox write failure")
            events += event
        }
    }

    /** 근거 승격 경로용 In-memory 스토리지(Mockito 매처 회피). put/delete 를 추적해 롤백 정리 검증. */
    class FakeStorage : AttachmentStoragePort {
        val putKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val deletedKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        override fun put(objectKey: String, bytes: ByteArray, contentType: String) { putKeys += objectKey }
        override fun presignGet(objectKey: String, disposition: AttachmentStoragePort.Disposition, ttl: java.time.Duration) =
            java.net.URI.create("http://test/$objectKey")
        override fun getBytes(objectKey: String): ByteArray = byteArrayOf(1, 2, 3)
        override fun delete(objectKey: String) { deletedKeys += objectKey }
    }

    @TestConfiguration
    class Config {
        @Bean @Primary
        fun recordingPublisher() = RecordingPublisher()

        @Bean @Primary
        fun fakeStorage(): AttachmentStoragePort = FakeStorage()
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var captures: AgentCaptureRepositoryPort
    @Autowired private lateinit var attempts: CaptureAttemptRepositoryPort
    @Autowired private lateinit var solutions: CaptureSolutionRepositoryPort
    @Autowired private lateinit var errorCases: ErrorCaseRepositoryPort
    @Autowired private lateinit var steps: StepRepositoryPort
    @Autowired private lateinit var caseSolutions: SolutionRepositoryPort
    @Autowired private lateinit var projectBindings: ProjectBindingRepositoryPort
    @Autowired private lateinit var publisher: RecordingPublisher
    @Autowired private lateinit var captureArtifacts: CaptureArtifactRepositoryPort
    @Autowired private lateinit var caseAttachments: ErrorCaseAttachmentRepositoryPort
    @Autowired private lateinit var storage: AttachmentStoragePort
    @Autowired private lateinit var importCapture: org.studieojavry.coreapi.agentcapture.application.usecase.ImportCaptureUseCase
    @Autowired private lateinit var registerSession: org.studieojavry.coreapi.agentcapture.application.usecase.RegisterCaptureSessionUseCase
    @Autowired private lateinit var createCapture: org.studieojavry.coreapi.agentcapture.application.usecase.CreateCaptureUseCase
    private lateinit var mvc: MockMvc

    private val userId = 7001L

    @BeforeEach
    fun setUp() {
        publisher.events.clear()
        publisher.failNext = false
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

    // seedCapture 봉인 후 revision=1. 전체 선택(a1,a2 순서 + s1).
    private val fullImport = """{"reviewedRevision":1,"selectedAttemptIds":["a1","a2"],"selectedSolutionIds":["s1"]}"""

    private fun doImport(uuid: UUID, user: Long, key: String, body: String = fullImport) =
        mvc.perform(
            post("/api/v1/captures/$uuid/import").with(asUser(user))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    @Test
    fun `봉인된 초안 반영 - 신규 PRIVATE·OPEN 케이스 + Step·Solution 매핑 + IMPORTED`() {
        val (uuid, captureId) = seedCapture(userId, seal = true)

        doImport(uuid, userId, "imp-happy")
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
    fun `같은 요청 재반영은 다른 키여도 같은 케이스를 200 으로 (CaptureImport 해시 멱등)`() {
        val (uuid, _) = seedCapture(userId, seal = true)

        val first = doImport(uuid, userId, "imp-a")
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val caseId1 = Regex("\"caseId\":(\\d+)").find(first)!!.groupValues[1]

        // 다른 Idempotency-Key + 같은 선택 → IMPORTED 상태에서 요청해시 일치 → 같은 케이스 200.
        doImport(uuid, userId, "imp-b")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.alreadyImported").value(true))
            .andExpect(jsonPath("$.caseId").value(caseId1.toInt()))
    }

    @Test
    fun `승인한 항목만 반영된다 (선택 Attempt 만 Step 으로)`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        // a2 만 선택(성공 시도). s1 은 a2 참조 → 반영됨.
        doImport(uuid, userId, "imp-sel", """{"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"]}""")
            .andExpect(status().isCreated)
        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        val createdSteps = steps.findAllByErrorCaseId(caseId)
        assertEquals(1, createdSteps.size)
        assertEquals(StepStatus.RESOLVED, createdSteps[0].status)
        assertEquals(1, caseSolutions.findAllByErrorCaseId(caseId).size)
    }

    @Test
    fun `오래된 reviewedRevision 반영은 409 REVISION_CONFLICT`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        doImport(uuid, userId, "imp-rev", """{"reviewedRevision":999,"selectedAttemptIds":["a1"],"selectedSolutionIds":[]}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
    }

    @Test
    fun `다른 선택으로 재반영은 409 CAPTURE_ALREADY_IMPORTED + caseId 안내`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        val first = doImport(uuid, userId, "imp-x").andExpect(status().isCreated).andReturn().response.contentAsString
        val caseId1 = Regex("\"caseId\":(\\d+)").find(first)!!.groupValues[1].toInt()

        // 다른 선택(a1 만) → 요청해시 불일치 → 409 + 이미 생성된 caseId 안내.
        doImport(uuid, userId, "imp-y", """{"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedSolutionIds":[]}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CAPTURE_ALREADY_IMPORTED"))
            .andExpect(jsonPath("$.caseId").value(caseId1))
    }

    @Test
    fun `markResolved=true 면 케이스가 RESOLVED 로 생성된다`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        doImport(uuid, userId, "imp-res", """{"reviewedRevision":1,"selectedAttemptIds":["a1","a2"],"selectedSolutionIds":["s1"],"markResolved":true}""")
            .andExpect(status().isCreated)
        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        assertEquals(ErrorCaseStatus.RESOLVED, errorCases.findById(caseId)!!.status)
    }

    @Test
    fun `봉인되지 않은(OPEN) 초안 반영은 409`() {
        val (uuid, _) = seedCapture(userId, seal = false)

        doImport(uuid, userId, "imp-open")
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
        doImport(imported, userId, "imp-disc").andExpect(status().isCreated)
        mvc.perform(post("/api/v1/captures/$imported/discard").with(asUser(userId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CAPTURE_ALREADY_IMPORTED"))
    }

    @Test
    fun `import 에 projectBindingId 지정 시 Case 에 귀속된다`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        val binding = projectBindings.save(
            ProjectBinding(ownerUserId = userId, projectKey = "checkout", createdAt = now(), updatedAt = now()),
        )
        doImport(
            uuid, userId, "imp-bind",
            """{"reviewedRevision":1,"selectedAttemptIds":["a1","a2"],"selectedSolutionIds":["s1"],"projectBindingId":${binding.id}}""",
        ).andExpect(status().isCreated)
        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        assertEquals(binding.id, errorCases.findById(caseId)!!.projectBindingId)
    }

    @Test
    fun `import clearProjectBinding=true 면 projectBindingId 지정해도 미귀속으로 반영`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        val binding = projectBindings.save(
            ProjectBinding(ownerUserId = userId, projectKey = "to-clear", createdAt = now(), updatedAt = now()),
        )
        doImport(
            uuid, userId, "imp-clear",
            """{"reviewedRevision":1,"selectedAttemptIds":["a1","a2"],"selectedSolutionIds":["s1"],"projectBindingId":${binding.id},"clearProjectBinding":true}""",
        ).andExpect(status().isCreated)
        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        assertNull(errorCases.findById(caseId)!!.projectBindingId)
    }

    @Test
    fun `전 구간 - 세션 상속 Capture 를 clearProjectBinding=true 로 import 하면 Case 미귀속`() {
        // 실제 세션 → Capture(상속) → Import 전 구간 회귀: 세션에서 받은 프로젝트를 Capture 가 상속한 상태에서도
        // clearProjectBinding 이 상속분(capture.projectBindingId)을 이겨 Case 가 미귀속(null)으로 반영되는지.
        val chainUser = 8120L
        val conn = UUID.randomUUID()
        val binding = projectBindings.save(
            ProjectBinding(ownerUserId = chainUser, projectKey = "inherited-chain", createdAt = now(), updatedAt = now()),
        )
        // 1) 세션이 projectBinding 을 받고
        val sessionUuid = UUID.randomUUID()
        registerSession.invoke(
            org.studieojavry.coreapi.agentcapture.application.usecase.RegisterSessionCommand(
                sessionUuid = sessionUuid, ownerUserId = chainUser, connectionId = conn,
                clientKind = "claude-code", clientVersion = "1", clientSessionId = "cs-chain",
                projectBindingId = binding.id,
            ),
        )
        // 2) Capture 가 세션에서 상속(명시 projectBindingId 없이) — 상속 자체를 먼저 확인.
        val capUuid = UUID.randomUUID()
        val created = createCapture.invoke(
            org.studieojavry.coreapi.agentcapture.application.usecase.CreateCaptureCommand(
                captureUuid = capUuid, ownerUserId = chainUser, connectionId = conn,
                title = "inherited", project = null, content = CaptureContent.EMPTY, sessionUuid = sessionUuid,
            ),
        )
        assertEquals(binding.id, created.projectBindingId)

        // 3) 봉인(READY_FOR_REVIEW).
        val sealed = captures.findByUuid(capUuid)!!
        sealed.complete(now())
        captures.save(sealed)
        val rev = captures.findByUuid(capUuid)!!.revision

        // 4) clearProjectBinding=true(요청에 projectBindingId 미지정) → 상속분이 있어도 Case 미귀속.
        doImport(capUuid, chainUser, "chain-clear", """{"reviewedRevision":$rev,"clearProjectBinding":true}""")
            .andExpect(status().isCreated)
        val caseId = captures.findByUuid(capUuid)!!.importedCaseId!!
        assertNull(errorCases.findById(caseId)!!.projectBindingId)
    }

    @Test
    fun `타인 소유 projectBindingId 는 404`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        val foreign = projectBindings.save(
            ProjectBinding(ownerUserId = 9999L, projectKey = "foreign", createdAt = now(), updatedAt = now()),
        )
        doImport(
            uuid, userId, "imp-bind-foreign",
            """{"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedSolutionIds":[],"projectBindingId":${foreign.id}}""",
        ).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PROJECT_BINDING_NOT_FOUND"))
    }

    @Test
    fun `타인 소유 초안 반영은 404`() {
        val (uuid, _) = seedCapture(owner = 9999L, seal = true)
        doImport(uuid, userId, "imp-404")
            .andExpect(status().isNotFound)
    }

    @Test
    fun `이벤트 저장(아웃박스) 실패 시 전체 롤백 - 부분 Case 없음, 초안 READY 유지`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        val casesBefore = errorCases.findRecentByOwner(userId, 100).size

        // Case·Step·Solution 과 이벤트 발행(아웃박스 저장)은 한 @Transactional.
        // 이벤트 저장이 실패하도록 주입 → 전체가 롤백되어야 한다(부분 Case 금지).
        publisher.failNext = true
        doImport(uuid, userId, "k-outbox-rollback")
            .andExpect(status().is5xxServerError)

        // 초안은 여전히 READY(IMPORTED 아님), 케이스 백링크 없음 = 반영 전체 롤백.
        val reloaded = captures.findByUuid(uuid)!!
        assertEquals(CaptureStatus.READY_FOR_REVIEW, reloaded.status)
        assertEquals(null, reloaded.importedCaseId)
        // Case/Step/Solution 어느 것도 영속되지 않음(부분 생성 없음).
        assertEquals(casesBefore, errorCases.findRecentByOwner(userId, 100).size)
    }

    @Test
    fun `반영 실패 후 재반영 가능 - 실패가 부분 상태를 남기지 않음(재시도 정합성)`() {
        val (uuid, _) = seedCapture(userId, seal = true)

        publisher.failNext = true
        doImport(uuid, userId, "k-retry")
            .andExpect(status().is5xxServerError)

        // 실패가 Case·백링크·이벤트·멱등영수증을 전혀 남기지 않았으므로, 같은 키로 재반영해도 정상 성공한다.
        publisher.failNext = false
        doImport(uuid, userId, "k-retry")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.alreadyImported").value(false))
            .andExpect(jsonPath("$.caseId").isNumber)

        assertEquals(CaptureStatus.IMPORTED, captures.findByUuid(uuid)!!.status)
        assertTrue(publisher.events.any { it.type == ActivityEventPublisherPort.Type.CASE_CREATED })
    }    
    
    @Test
    fun `검토 완료 이력 - IMPORTED·DISCARDED 를 caseId 백링크와 함께 조회`() {
        val histUser = 8101L // 테스트 간 DB 공유 → 이력 격리 위해 전용 유저.
        val (impUuid, _) = seedCapture(histUser, seal = true)
        doImport(impUuid, histUser, "hist-imp").andExpect(status().isCreated)
        val caseId = captures.findByUuid(impUuid)!!.importedCaseId!!

        val (discUuid, _) = seedCapture(histUser, seal = true)
        mvc.perform(post("/api/v1/captures/$discUuid/discard").with(asUser(histUser))).andExpect(status().isOk)

        // 전체 이력: 2건(IMPORTED + DISCARDED).
        mvc.perform(get("/api/v1/captures/history").with(asUser(histUser)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))

        // status=IMPORTED 필터: 1건 + caseId 백링크.
        mvc.perform(get("/api/v1/captures/history").param("status", "IMPORTED").with(asUser(histUser)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].status").value("IMPORTED"))
            .andExpect(jsonPath("$.items[0].caseId").value(caseId.toInt()))

        // 종단 아닌 status → 400.
        mvc.perform(get("/api/v1/captures/history").param("status", "READY_FOR_REVIEW").with(asUser(histUser)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `검토 큐 keyset 페이지네이션 - limit·cursor 로 전부 중복없이 순회(updatedAt DESC, id DESC)`() {
        val pageUser = 8110L
        // 3건 봉인(READY_FOR_REVIEW). 시드 순서대로 updatedAt 증가 → 최신순 = 역순.
        val u1 = seedCapture(pageUser, seal = true).first
        val u2 = seedCapture(pageUser, seal = true).first
        val u3 = seedCapture(pageUser, seal = true).first

        // 1페이지(limit=2): 최신 2건[u3,u2], hasNext=true, nextCursor 존재.
        val r1 = mvc.perform(get("/api/v1/captures").param("limit", "2").with(asUser(pageUser)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].captureUuid").value(u3.toString()))
            .andExpect(jsonPath("$.items[1].captureUuid").value(u2.toString()))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty)
            .andReturn()
        val cursor = com.jayway.jsonpath.JsonPath.read<String>(r1.response.contentAsString, "$.nextCursor")

        // 2페이지(cursor): 남은 1건[u1], hasNext=false, nextCursor=null. 1페이지와 겹치지 않음.
        mvc.perform(get("/api/v1/captures").param("limit", "2").param("cursor", cursor).with(asUser(pageUser)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].captureUuid").value(u1.toString()))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())

        // 잘못된 cursor 는 무시하고 첫 페이지부터(3건 다 보이되 limit 로 상한).
        mvc.perform(get("/api/v1/captures").param("limit", "50").param("cursor", "!!garbage!!").with(asUser(pageUser)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(3))
    }

    @Test
    fun `검토 이력 keyset 페이지네이션 - IMPORTED·DISCARDED 혼합을 cursor 로 순회`() {
        val hpUser = 8111L
        // 3건 종단화(IMPORTED 1 + DISCARDED 2) → 최신순 혼합 스트림.
        val imp = seedCapture(hpUser, seal = true).first
        doImport(imp, hpUser, "hp-imp").andExpect(status().isCreated)
        val d1 = seedCapture(hpUser, seal = true).first
        mvc.perform(post("/api/v1/captures/$d1/discard").with(asUser(hpUser))).andExpect(status().isOk)
        val d2 = seedCapture(hpUser, seal = true).first
        mvc.perform(post("/api/v1/captures/$d2/discard").with(asUser(hpUser))).andExpect(status().isOk)

        // 1페이지(limit=2): 최신 2건, hasNext=true.
        val r1 = mvc.perform(get("/api/v1/captures/history").param("limit", "2").with(asUser(hpUser)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andReturn()
        val cursor = com.jayway.jsonpath.JsonPath.read<String>(r1.response.contentAsString, "$.nextCursor")

        // 2페이지: 남은 1건, 끝.
        mvc.perform(get("/api/v1/captures/history").param("limit", "2").param("cursor", cursor).with(asUser(hpUser)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
    }

    @Test
    fun `폐기 복원 - DISCARDED 를 READY 로 되돌리고 검토 큐에 다시 포함`() {
        val restoreUser = 8102L
        val (uuid, _) = seedCapture(restoreUser, seal = true)
        mvc.perform(post("/api/v1/captures/$uuid/discard").with(asUser(restoreUser))).andExpect(status().isOk)

        mvc.perform(post("/api/v1/captures/$uuid/restore").with(asUser(restoreUser)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("READY_FOR_REVIEW"))
        assertEquals(CaptureStatus.READY_FOR_REVIEW, captures.findByUuid(uuid)!!.status)

        // 복원됐으니 큐에 다시 보인다.
        mvc.perform(get("/api/v1/captures").with(asUser(restoreUser)))
            .andExpect(jsonPath("$.items[?(@.captureUuid=='$uuid')]").exists())

        // 이미 READY 라 재복원은 409.
        mvc.perform(post("/api/v1/captures/$uuid/restore").with(asUser(restoreUser)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CAPTURE_NOT_RESTORABLE"))
    }

    @Test
    fun `근거 개별 제외(B) - 선택 Attempt 라도 미선택 근거는 반영되지 않는다`() {
        // a1 이 art1·art2 두 근거를 참조하지만, 사용자는 art1 만 선택(art2 제외).
        val uuid = UUID.randomUUID()
        val saved = captures.save(
            AgentCapture.start(
                captureUuid = uuid, ownerUserId = userId, originatingConnectionId = UUID.randomUUID(),
                title = "NPE", project = "svc", content = CaptureContent(symptom = "NPE"), now = now(),
            )
        )
        val captureId = saved.id!!
        attempts.save(CaptureAttempt.create(captureId, "a1", 0, AttemptResult.SUCCEEDED, "fix",
            AttemptDetail(title = "fix", action = "run", evidenceRefs = listOf("art1", "art2")), now()))
        listOf("art1", "art2").forEach {
            captureArtifacts.save(CaptureArtifact.completed(
                captureId = captureId, clientArtifactId = it, objectKey = "obj/$it", sha256 = "s",
                contentType = "image/png", sizeBytes = 3, fileName = "$it.png", uploaderUserId = userId, now = now(),
            ))
        }
        val c = captures.findByUuid(uuid)!!; c.complete(now()); captures.save(c)

        doImport(uuid, userId, "imp-exclude",
            """{"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedSolutionIds":[],"selectedArtifactIds":["art1"]}""")
            .andExpect(status().isCreated)

        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        val atts = caseAttachments.findAllByErrorCaseId(caseId)
        assertEquals(1, atts.size, "선택한 art1 만 반영")
        val step = steps.findAllByErrorCaseId(caseId).single()
        assertTrue(step.body?.contains("@attach(${atts.single().markerId})") == true)
    }

    @Test
    fun `엄격 검증 - 존재하지 않는 Solution 선택은 422`() {
        val uuid = seedCaptureWithRichSolution(userId)
        doImport(uuid, userId, "imp-badsol",
            """{"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["nope"]}""")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("UNKNOWN_SOLUTION_REFERENCE"))
    }

    @Test
    fun `엄격 검증 - 존재하지 않는 Artifact 선택은 422`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        doImport(uuid, userId, "imp-badart",
            """{"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedSolutionIds":[],"selectedArtifactIds":["ghost"]}""")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("UNKNOWN_ARTIFACT_REFERENCE"))
    }

    @Test
    fun `중복 Attempt 선택은 Step 을 중복 생성하지 않는다`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        doImport(uuid, userId, "imp-dup",
            """{"reviewedRevision":1,"selectedAttemptIds":["a1","a1"],"selectedSolutionIds":[]}""")
            .andExpect(status().isCreated)
        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        assertEquals(1, steps.findAllByErrorCaseId(caseId).size)
    }

    @Test
    fun `Attempt 선택 순서가 다르면 재반영은 다른 요청으로 취급되어 409`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        doImport(uuid, userId, "imp-ord1",
            """{"reviewedRevision":1,"selectedAttemptIds":["a1","a2"],"selectedSolutionIds":[]}""")
            .andExpect(status().isCreated)
        // 같은 집합, 다른 순서 → Step 순서가 달라지므로 멱등 재생이 아니라 충돌(requestHash 가 순서 보존).
        doImport(uuid, userId, "imp-ord2",
            """{"reviewedRevision":1,"selectedAttemptIds":["a2","a1"],"selectedSolutionIds":[]}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CAPTURE_ALREADY_IMPORTED"))
    }

    @Test
    fun `다른 Capture 에 같은 Idempotency-Key·본문을 써도 서로의 결과를 재생하지 않는다`() {
        val user = 8201L
        val (a, _) = seedCapture(user, seal = true)
        val (b, _) = seedCapture(user, seal = true)
        val body = """{"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedSolutionIds":[]}"""
        val key = "shared-key"
        doImport(a, user, key, body).andExpect(status().isCreated)
        val caseA = captures.findByUuid(a)!!.importedCaseId!!
        doImport(b, user, key, body).andExpect(status().isCreated)
        val caseB = captures.findByUuid(b)!!.importedCaseId!!
        assertNotEquals(caseA, caseB) // B 가 A 결과를 재생하지 않고 자기 Case 로 반영
        assertEquals(CaptureStatus.IMPORTED, captures.findByUuid(b)!!.status)
    }

    @Test
    fun `정책 - 선택한 Solution 의 참조 Attempt 를 하나도 선택 안 하면 422`() {
        val uuid = seedCaptureWithRichSolution(userId) // s1 은 a2 참조
        // a2 를 선택하지 않고 s1 만 선택 → s1 의 참조 Step 이 0개 → 조용한 skip 대신 422.
        doImport(uuid, userId, "imp-solnostep",
            """{"reviewedRevision":1,"selectedAttemptIds":[],"selectedSolutionIds":["s1"]}""")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("SOLUTION_WITHOUT_SELECTED_ATTEMPT"))
    }

    @Test
    fun `실패 복구 - 반영 롤백 시 승격한 스토리지 파일이 삭제된다`() {
        val fake = storage as FakeStorage
        // a1(근거 art1) + s1(a2 참조). art1·a1 선택 + s1 선택하되 a2 미선택 → 승격 후 솔루션 루프에서 422 → 롤백.
        val uuid = UUID.randomUUID()
        val saved = captures.save(
            AgentCapture.start(
                captureUuid = uuid, ownerUserId = userId, originatingConnectionId = UUID.randomUUID(),
                title = "NPE", project = "svc", content = CaptureContent(symptom = "NPE"), now = now(),
            )
        )
        val captureId = saved.id!!
        attempts.save(CaptureAttempt.create(captureId, "a1", 0, AttemptResult.FAILED, "repro",
            AttemptDetail(title = "repro", action = "run", evidenceRefs = listOf("art1")), now()))
        attempts.save(CaptureAttempt.create(captureId, "a2", 1, AttemptResult.SUCCEEDED, "fix",
            AttemptDetail(title = "fix", action = "x"), now()))
        captureArtifacts.save(CaptureArtifact.completed(
            captureId = captureId, clientArtifactId = "art1", objectKey = "obj/art1", sha256 = "s",
            contentType = "image/png", sizeBytes = 3, fileName = "art1.png", uploaderUserId = userId, now = now(),
        ))
        solutions.save(CaptureSolutionCandidate.create(captureId, "s1", "fix",
            SolutionContent(clientAttemptIds = listOf("a2")), now()))
        val c = captures.findByUuid(uuid)!!; c.complete(now()); captures.save(c)

        val putsBefore = fake.putKeys.toSet()
        doImport(uuid, userId, "imp-rollback", """
            {"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedSolutionIds":["s1"],"selectedArtifactIds":["art1"]}
        """.trimIndent())
            .andExpect(status().isUnprocessableEntity) // s1 참조 a2 미선택 → 422(롤백)

        val newPuts = fake.putKeys - putsBefore
        assertTrue(newPuts.isNotEmpty(), "승격 과정에서 스토리지 put 이 있어야 함")
        assertTrue(fake.deletedKeys.containsAll(newPuts), "롤백 시 승격한 파일이 삭제돼야 함: $newPuts")
        // 롤백됐으니 Case 도 안 생김.
        assertEquals(CaptureStatus.READY_FOR_REVIEW, captures.findByUuid(uuid)!!.status)
    }

    @Test
    fun `동시 Import - 정확히 한 Case 만 생성되고 Step 중복 없음`() {
        val user = 8301L
        val (uuid, _) = seedCapture(user, seal = true)
        val cmd = org.studieojavry.coreapi.agentcapture.application.usecase.ImportCaptureCommand(
            captureUuid = uuid, reviewerUserId = user, reviewedRevision = 1,
            selectedAttemptIds = listOf("a1", "a2"), selectedSolutionIds = emptyList(),
        )
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        val futures = (1..2).map {
            pool.submit(java.util.concurrent.Callable {
                start.await()
                runCatching { importCapture.invoke(cmd) }
            })
        }
        start.countDown()
        val results = futures.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        pool.shutdown()

        val successes = results.mapNotNull { it.getOrNull() }
        assertTrue(successes.isNotEmpty(), "적어도 하나는 성공")
        assertEquals(1, successes.map { it.caseId }.distinct().size, "성공은 모두 같은 Case")
        val reloaded = captures.findByUuid(uuid)!!
        assertEquals(CaptureStatus.IMPORTED, reloaded.status)
        // 경합으로 Step 이 중복(4개) 생기지 않고 정확히 2개(a1,a2).
        assertEquals(2, steps.findAllByErrorCaseId(reloaded.importedCaseId!!).size)
    }

    @Test
    fun `폐기 복원 - OPEN 초안은 READY 가 아니라 OPEN 으로 복원(봉인 검증 우회 방지)`() {
        val user = 8401L
        val (uuid, _) = seedCapture(user, seal = false) // 봉인 전 OPEN(completedAt=null)
        mvc.perform(post("/api/v1/captures/$uuid/discard").with(asUser(user))).andExpect(status().isOk)

        mvc.perform(post("/api/v1/captures/$uuid/restore").with(asUser(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("OPEN")) // READY 아님
        assertEquals(CaptureStatus.OPEN, captures.findByUuid(uuid)!!.status)
    }

    @Test
    fun `검토 중 수정 - Step 본문의 미연결 @attach 는 422`() {
        val (uuid, _) = seedCapture(userId, seal = true)
        // 승격한 근거가 없는데(selectedArtifactIds 없음) 본문에 외부 @attach → INVALID_STEP_BODY_MARKER.
        doImport(uuid, userId, "imp-foreignmarker", """
            {"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedSolutionIds":[],
             "stepEdits":{"a1":{"body":"로그 @attach(foreign123) 참고"}}}
        """.trimIndent())
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("INVALID_STEP_BODY_MARKER"))
    }

    @Test
    fun `정책 - PASSED 검증은 근거(command·testId·evidence) 최소 1개 필요`() {
        val uuid = seedCaptureWithRichSolution(userId)
        doImport(uuid, userId, "imp-noevidence", """
            {"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"],
             "solutionEdits":{"s1":{"verification":{"result":"PASSED","executed":true}}}}
        """.trimIndent())
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("VERIFICATION_EVIDENCE_REQUIRED"))
    }

    @Test
    fun `정책 - 허용되지 않은 verification result 코드는 422`() {
        val uuid = seedCaptureWithRichSolution(userId)
        doImport(uuid, userId, "imp-badresult", """
            {"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"],
             "solutionEdits":{"s1":{"verification":{"result":"MAYBE","executed":true,"evidence":"x"}}}}
        """.trimIndent())
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"))
    }

    /** s1 에 description + verification 을 실은 봉인 초안. (#3 이관/정정 검증용) */
    private fun seedCaptureWithRichSolution(
        owner: Long,
        verification: Verification? = Verification(
            result = VerificationResult.PASSED, command = "./gradlew test", executed = true, evidence = "42 tests passed",
        ),
    ): UUID {
        val uuid = UUID.randomUUID()
        val saved = captures.save(
            AgentCapture.start(
                captureUuid = uuid, ownerUserId = owner, originatingConnectionId = UUID.randomUUID(),
                title = "NPE on checkout", project = "checkout-svc",
                content = CaptureContent(symptom = "NPE at total()"), now = now(),
            )
        )
        val captureId = saved.id!!
        attempts.save(CaptureAttempt.create(captureId, "a2", 0, AttemptResult.SUCCEEDED, "fix",
            AttemptDetail(title = "Null-guard the price", action = "add ?: 0"), now()))
        solutions.save(CaptureSolutionCandidate.create(captureId, "s1", "Guard null price",
            SolutionContent(
                description = "엘비스 연산자로 null 가드",
                clientAttemptIds = listOf("a2"),
                verification = verification,
            ), now()))
        val c = captures.findByUuid(uuid)!!; c.complete(now()); captures.save(c)
        return uuid
    }

    @Test
    fun `반영 - Solution 후보의 상세 설명·검증 정보가 Case Solution 으로 이관된다`() {
        val uuid = seedCaptureWithRichSolution(userId)

        doImport(uuid, userId, "imp-rich",
            """{"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"]}""")
            .andExpect(status().isCreated)

        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        val solution = caseSolutions.findAllByErrorCaseId(caseId).single()
        assertEquals("엘비스 연산자로 null 가드", solution.description)
        assertNotNull(solution.verification)
        val v = solution.verification!!
        assertEquals("PASSED", v.result)
        assertEquals("./gradlew test", v.command)
        assertTrue(v.executed)
        assertEquals("42 tests passed", v.evidence)
    }

    @Test
    fun `검토 중 수정 - Case·Step·Solution override 가 반영된다`() {
        val uuid = seedCaptureWithRichSolution(userId)

        doImport(uuid, userId, "imp-edit", """
            {"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"],
             "editedTitle":"체크아웃 NPE 수정","editedDescription":"리뷰 중 다듬은 본문",
             "stepEdits":{"a2":{"title":"가격 null 가드","body":"수정한 본문"}},
             "solutionEdits":{"s1":{"title":"최종 해결","description":"리뷰 중 다듬은 설명"}}}
        """.trimIndent())
            .andExpect(status().isCreated)

        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        val case = errorCases.findById(caseId)!!
        assertEquals("체크아웃 NPE 수정", case.title)
        assertEquals("리뷰 중 다듬은 본문", case.description)

        val step = steps.findAllByErrorCaseId(caseId).single()
        assertEquals("가격 null 가드", step.title)
        assertEquals("수정한 본문", step.body)

        val solution = caseSolutions.findAllByErrorCaseId(caseId).single()
        assertEquals("최종 해결", solution.title)
        assertEquals("리뷰 중 다듬은 설명", solution.description)
    }

    @Test
    fun `검토 중 수정 - 최종 Step 상태를 정정할 수 있다`() {
        val (uuid, _) = seedCapture(userId, seal = true) // a1=FAILED(기본 mapStatus=FAILED)
        doImport(uuid, userId, "imp-stepstatus", """
            {"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedSolutionIds":[],
             "stepEdits":{"a1":{"status":"RESOLVED"}}}
        """.trimIndent())
            .andExpect(status().isCreated)
        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        assertEquals(StepStatus.RESOLVED, steps.findAllByErrorCaseId(caseId).single { it.orderIndex == 0 }.status)
    }

    @Test
    fun `검토 중 수정 - Solution 검증 정보를 정정할 수 있다`() {
        val uuid = seedCaptureWithRichSolution(userId) // 캡처: PASSED/executed=true
        doImport(uuid, userId, "imp-verif", """
            {"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"],
             "solutionEdits":{"s1":{"verification":{"result":"FAILED","executed":true,"evidence":"재현됨"}}}}
        """.trimIndent())
            .andExpect(status().isCreated)
        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        val v = caseSolutions.findAllByErrorCaseId(caseId).single().verification!!
        assertEquals("FAILED", v.result)
        assertEquals("재현됨", v.evidence)
    }

    @Test
    fun `검토 중 수정 - 검증 정보 전체 제외(clearVerification)`() {
        val uuid = seedCaptureWithRichSolution(userId)
        doImport(uuid, userId, "imp-clearverif", """
            {"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"],
             "solutionEdits":{"s1":{"clearVerification":true}}}
        """.trimIndent())
            .andExpect(status().isCreated)
        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        assertEquals(null, caseSolutions.findAllByErrorCaseId(caseId).single().verification)
    }

    @Test
    fun `정책 - 정정한 검증이 실행안함+PASSED 면 422`() {
        val uuid = seedCaptureWithRichSolution(userId)
        doImport(uuid, userId, "imp-badverif", """
            {"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"],
             "solutionEdits":{"s1":{"verification":{"result":"PASSED","executed":false}}}}
        """.trimIndent())
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("UNEXECUTED_VERIFICATION_PASSED"))
    }

    @Test
    fun `정책 - 캡처 원본 검증이 실행안함+PASSED 면 반영도 422(제외하거나 정정 필요)`() {
        // 캡처가 AI 가 실행 없이 PASSED 로 표기한 검증을 담고 있어도, 반영(확정) 시점에 거른다.
        val uuid = seedCaptureWithRichSolution(
            userId,
            verification = Verification(result = VerificationResult.PASSED, executed = false),
        )
        doImport(uuid, userId, "imp-badcapverif",
            """{"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"]}""")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("UNEXECUTED_VERIFICATION_PASSED"))

        // clearVerification 으로 제외하면 반영 성공.
        doImport(uuid, userId, "imp-badcapverif-cleared", """
            {"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"],
             "solutionEdits":{"s1":{"clearVerification":true}}}
        """.trimIndent())
            .andExpect(status().isCreated)
    }

    @Test
    fun `검토 중 수정 - 선택되지 않은 Attempt 편집 키는 422`() {
        val uuid = seedCaptureWithRichSolution(userId)
        // a2 만 선택했는데 stepEdits 가 미선택 a1 을 가리킴 → 422.
        doImport(uuid, userId, "imp-edit-bad", """
            {"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":["s1"],
             "stepEdits":{"a1":{"title":"없는 선택"}}}
        """.trimIndent())
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("UNKNOWN_ATTEMPT_REFERENCE"))
    }

    @Test
    fun `반영 - Attempt 근거는 그 Step 본문에 @attach 로 삽입되고 Case 에 링크된다`() {
        // a1 의 근거로 art1 을 단다.
        val uuid = UUID.randomUUID()
        val saved = captures.save(
            AgentCapture.start(
                captureUuid = uuid, ownerUserId = userId, originatingConnectionId = UUID.randomUUID(),
                title = "NPE", project = "svc", content = CaptureContent(symptom = "NPE"), now = now(),
            )
        )
        val captureId = saved.id!!
        attempts.save(CaptureAttempt.create(captureId, "a1", 0, AttemptResult.FAILED, "repro",
            AttemptDetail(title = "재현", action = "run", evidenceRefs = listOf("art1")), now()))
        captureArtifacts.save(CaptureArtifact.completed(
            captureId = captureId, clientArtifactId = "art1", objectKey = "obj/art1", sha256 = "deadbeef",
            contentType = "image/png", sizeBytes = 3, fileName = "shot.png", uploaderUserId = userId, now = now(),
        ))
        val c = captures.findByUuid(uuid)!!; c.complete(now()); captures.save(c)

        doImport(uuid, userId, "imp-ev",
            """{"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedSolutionIds":[],"selectedArtifactIds":["art1"]}""")
            .andExpect(status().isCreated)

        val caseId = captures.findByUuid(uuid)!!.importedCaseId!!
        // 근거가 Case 에 첨부로 링크됨
        val att = caseAttachments.findAllByErrorCaseId(caseId).single()
        // 그 @attach 토큰이 a1 의 Step 본문에 삽입됨
        val step = steps.findAllByErrorCaseId(caseId).single()
        assertTrue(
            step.body?.contains("@attach(${att.markerId})") == true,
            "Step 본문에 근거 @attach 토큰이 삽입되어야 함: ${step.body}",
        )
    }

    @Test
    fun `검토 중 수정 - 선택되지 않은 Solution 편집 키는 422`() {
        val uuid = seedCaptureWithRichSolution(userId)
        doImport(uuid, userId, "imp-edit-bad2", """
            {"reviewedRevision":1,"selectedAttemptIds":["a2"],"selectedSolutionIds":[],
             "solutionEdits":{"s1":{"title":"미선택 후보"}}}
        """.trimIndent())
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("UNKNOWN_SOLUTION_REFERENCE"))
    }
}
