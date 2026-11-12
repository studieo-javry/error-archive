package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Agent Capture(P0) PR2 초안 수집 흐름 — Testcontainers Postgres + Flyway + ddl-auto=validate.
 * validate 통과 자체가 V19 ↔ 엔티티 정합 증명. agent principal 은 InternalAuthentication(cid/scp)로 주입.
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
class AgentCaptureFlowIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var om: ObjectMapper
    private lateinit var mvc: MockMvc

    private val userId = 5001L
    private val connectionId = UUID.randomUUID().toString()
    private val writeScopes = listOf("capture:read", "capture:write")

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun agent(uid: Long = userId, conn: String = connectionId, scopes: List<String> = writeScopes) =
        authentication(
            InternalAuthentication(
                uid,
                scopes.map { SimpleGrantedAuthority("SCOPE_$it") },
                "gateway",
                conn,
                scopes,
            )
        )

    private fun createCapture(uuid: UUID, key: String, title: String = "NPE on login"): String =
        mvc.perform(
            post("/api/v1/agent/captures").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "captureUuid" to uuid.toString(),
                    "title" to title,
                    "content" to mapOf("symptom" to "500 on /login", "observedFacts" to listOf("stacktrace X")),
                ))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString

    @Test
    fun `오류-실패시도2-검증-해결후보-봉인 전체 흐름`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "k-create")

        // 조회
        mvc.perform(get("/api/v1/agent/captures/$uuid").with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.capture.status").value("OPEN"))
            .andExpect(jsonPath("$.capture.revision").value(0))

        // 기본 정보 수정 → revision 1
        mvc.perform(
            patch("/api/v1/agent/captures/$uuid").with(agent())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("expectedRevision" to 0, "title" to "NPE on login (refined)"))),
        ).andExpect(status().isOk).andExpect(jsonPath("$.revision").value(1))

        // 실패 Attempt 2개
        addAttempt(uuid, "a1", "FAILED", "k-a1")
        addAttempt(uuid, "a2", "FAILED", "k-a2")
        // 성공 검증 Attempt
        addAttempt(uuid, "a3", "SUCCEEDED", "k-a3")

        // Solution 후보(존재하는 Attempt 참조)
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/solutions").with(agent())
                .header("Idempotency-Key", "k-sol")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "clientSolutionId" to "s1",
                    "title" to "null-check guard",
                    "content" to mapOf(
                        "description" to "add guard",
                        "clientAttemptIds" to listOf("a3"),
                        "verification" to mapOf("result" to "PASSED", "scope" to "LOCAL", "executed" to true, "command" to "./gradlew test"),
                    ),
                ))),
        ).andExpect(status().isCreated)

        // 봉인 → READY_FOR_REVIEW
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/complete").with(agent())
                .header("Idempotency-Key", "k-complete")
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andExpect(status().isOk).andExpect(jsonPath("$.status").value("READY_FOR_REVIEW"))

        // 봉인 후 에이전트 쓰기 → 409 CAPTURE_SEALED
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/attempts").with(agent())
                .header("Idempotency-Key", "k-after")
                .contentType(MediaType.APPLICATION_JSON)
                .content(attemptBody("a9", "FAILED")),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CAPTURE_SEALED"))
    }

    @Test
    fun `동일 키 재전송은 중복 없이 재생, 다른 payload 는 409`() {
        val uuid = UUID.randomUUID()
        val first = createCapture(uuid, "dup-key")
        val replay = createCapture(uuid, "dup-key")
        // 같은 결과 재생(captureUuid 동일)
        assert(om.readTree(first)["captureUuid"] == om.readTree(replay)["captureUuid"])

        // 같은 키·다른 payload → 409 IDEMPOTENCY_CONFLICT
        mvc.perform(
            post("/api/v1/agent/captures").with(agent())
                .header("Idempotency-Key", "dup-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("captureUuid" to UUID.randomUUID().toString(), "title" to "different"))),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
    }

    @Test
    fun `오래된 revision 수정은 409 REVISION_CONFLICT`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "k1")
        mvc.perform(
            patch("/api/v1/agent/captures/$uuid").with(agent())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("expectedRevision" to 7, "title" to "x"))),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
    }

    @Test
    fun `존재하지 않는 Attempt 를 참조한 Solution 은 422`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "k2")
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/solutions").with(agent())
                .header("Idempotency-Key", "k-sol2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "clientSolutionId" to "s9", "title" to "bad",
                    "content" to mapOf("clientAttemptIds" to listOf("ghost")),
                ))),
        ).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("UNKNOWN_ATTEMPT_REFERENCE"))
    }

    @Test
    fun `Attempt 참조가 하나도 없는 Solution 은 422 SOLUTION_WITHOUT_ATTEMPT`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "k-sol-empty")
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/solutions").with(agent())
                .header("Idempotency-Key", "k-sol-empty-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "clientSolutionId" to "s-empty", "title" to "no ref",
                    "content" to mapOf("clientAttemptIds" to emptyList<String>()),
                ))),
        ).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("SOLUTION_WITHOUT_ATTEMPT"))
    }

    @Test
    fun `PASSED 검증에 근거가 없으면 422 VERIFICATION_EVIDENCE_REQUIRED`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "k-sol-noevid")
        addAttempt(uuid, "a1", "SUCCEEDED", "k-a1-noevid")
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/solutions").with(agent())
                .header("Idempotency-Key", "k-sol-noevid-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "clientSolutionId" to "s-noevid", "title" to "passed but no evidence",
                    "content" to mapOf(
                        "clientAttemptIds" to listOf("a1"),
                        "verification" to mapOf("result" to "PASSED", "executed" to true),
                    ),
                ))),
        ).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("VERIFICATION_EVIDENCE_REQUIRED"))
    }

    @Test
    fun `실행하지 않은(executed=false) PASSED 는 수집 시점에 422 UNEXECUTED_VERIFICATION_PASSED`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "k-sol-unexec")
        addAttempt(uuid, "a1", "SUCCEEDED", "k-a1-unexec")
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/solutions").with(agent())
                .header("Idempotency-Key", "k-sol-unexec-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "clientSolutionId" to "s-unexec", "title" to "passed but not executed",
                    "content" to mapOf(
                        "clientAttemptIds" to listOf("a1"),
                        // 근거(command)는 있지만 executed=false → import 전에 수집에서 바로 거부(정책 대칭).
                        "verification" to mapOf("result" to "PASSED", "executed" to false, "command" to "./gradlew test"),
                    ),
                ))),
        ).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("UNEXECUTED_VERIFICATION_PASSED"))
    }

    @Test
    fun `Solution 수정(PATCH) 으로 title·verification 교체가 반영된다`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "k-us")
        addAttempt(uuid, "a1", "SUCCEEDED", "k-us-a1")
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/solutions").with(agent())
                .header("Idempotency-Key", "k-us-sol")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "clientSolutionId" to "s1", "title" to "초안 해결",
                    "content" to mapOf("clientAttemptIds" to listOf("a1")),
                ))),
        ).andExpect(status().isCreated)
        // 수정: title + verification 통째 교체
        mvc.perform(
            patch("/api/v1/agent/captures/$uuid/solutions/s1").with(agent())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "title" to "정정된 해결",
                    "content" to mapOf(
                        "clientAttemptIds" to listOf("a1"),
                        "verification" to mapOf("result" to "PASSED", "executed" to true, "command" to "./gradlew test"),
                    ),
                ))),
        ).andExpect(status().isOk).andExpect(jsonPath("$.title").value("정정된 해결"))
        // 영속 확인
        mvc.perform(get("/api/v1/agent/captures/$uuid").with(agent()))
            .andExpect(jsonPath("$.solutions[0].title").value("정정된 해결"))
    }

    @Test
    fun `모르는 clientSolutionId 수정은 422 UNKNOWN_SOLUTION_REFERENCE`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "k-us404")
        addAttempt(uuid, "a1", "SUCCEEDED", "k-us404-a1")
        mvc.perform(
            patch("/api/v1/agent/captures/$uuid/solutions/does-not-exist").with(agent())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "title" to "x", "content" to mapOf("clientAttemptIds" to listOf("a1")),
                ))),
        ).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("UNKNOWN_SOLUTION_REFERENCE"))
    }

    @Test
    fun `타인 소유 초안 조회는 404`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "k3")
        mvc.perform(get("/api/v1/agent/captures/$uuid").with(agent(uid = 9999L)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `capture-write scope 없는 토큰의 생성은 403`() {
        mvc.perform(
            post("/api/v1/agent/captures").with(agent(scopes = listOf("capture:read")))
                .header("Idempotency-Key", "k-noscope")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("captureUuid" to UUID.randomUUID().toString(), "title" to "x"))),
        ).andExpect(status().isForbidden)
    }

    private fun addAttempt(uuid: UUID, clientId: String, result: String, key: String) {
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/attempts").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(attemptBody(clientId, result)),
        ).andExpect(status().isCreated)
    }

    private fun attemptBody(clientId: String, result: String): String =
        om.writeValueAsString(mapOf(
            "clientAttemptId" to clientId,
            "result" to result,
            "detail" to mapOf("title" to "try $clientId", "action" to "ran something"),
        ))
}
