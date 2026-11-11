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
import org.springframework.http.MediaType
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
import org.studieojavry.coreapi.agentcapture.application.port.CaptureArtifactRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSolutionRepositoryPort
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

    /** 발행 이벤트를 기록해 origin/type 을 검증(@Primary 로 Noop 대체). */
    class RecordingPublisher : ActivityEventPublisherPort {
        val events = mutableListOf<ActivityEventPublisherPort.ActivityEvent>()
        override fun publish(event: ActivityEventPublisherPort.ActivityEvent) { events += event }
    }

    /** 근거 승격 경로용 In-memory 스토리지(Mockito 매처 회피). bytes 저장/반환, presign 은 더미 URI. */
    class FakeStorage : AttachmentStoragePort {
        override fun put(objectKey: String, bytes: ByteArray, contentType: String) {}
        override fun presignGet(objectKey: String, disposition: AttachmentStoragePort.Disposition, ttl: java.time.Duration) =
            java.net.URI.create("http://test/$objectKey")
        override fun getBytes(objectKey: String): ByteArray = byteArrayOf(1, 2, 3)
        override fun delete(objectKey: String) {}
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
    @Autowired private lateinit var publisher: RecordingPublisher
    @Autowired private lateinit var captureArtifacts: CaptureArtifactRepositoryPort
    @Autowired private lateinit var caseAttachments: ErrorCaseAttachmentRepositoryPort
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
    fun `타인 소유 초안 반영은 404`() {
        val (uuid, _) = seedCapture(owner = 9999L, seal = true)
        doImport(uuid, userId, "imp-404")
            .andExpect(status().isNotFound)
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
