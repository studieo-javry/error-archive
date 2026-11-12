package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.core.authority.SimpleGrantedAuthority
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
import org.studieojavry.coreapi.agentcapture.application.port.ProjectBindingRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBinding
import org.studieojavry.coreapi.agentcapture.domain.model.StoragePolicy
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorSnapshot
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.solution.domain.model.SolutionVerification
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * 과거 Case 조회 재설계(#161 후속) — find_cases/get_case 가 ProjectBinding 독립임을 검증.
 *  - 바인딩 미귀속(null) Case 도 본인 소유면 검색·상세 조회된다.
 *  - query 로 제목·설명·예외 검색, projectBindingId 는 선택 필터.
 *  - get_case 는 Step 본문·Solution 설명·검증을 반환, 타인 소유는 404.
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
class AgentCaseReadIntegrationTest {

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var errorCases: ErrorCaseRepositoryPort
    @Autowired private lateinit var steps: StepRepositoryPort
    @Autowired private lateinit var caseSolutions: SolutionRepositoryPort
    @Autowired private lateinit var bindings: ProjectBindingRepositoryPort
    private lateinit var mvc: MockMvc

    private val userId = 7700L
    private val conn = UUID.randomUUID().toString()

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun agent(scopes: List<String> = listOf("case:read")) =
        authentication(InternalAuthentication(userId, scopes.map { SimpleGrantedAuthority("SCOPE_$it") }, "gateway", conn, scopes))

    private fun newCase(
        owner: Long, title: String, description: String? = null,
        exceptionClass: String? = null, projectBindingId: Long? = null,
    ): Long = errorCases.save(
        ErrorCase.create(
            ownerUserId = owner, title = title, project = null,
            snapshot = exceptionClass?.let { ErrorSnapshot(null, it, "boom", null, null) },
            description = description,
            meta = Meta.create(workspaceId = null, severityCode = null),
            visibility = Visibility.PRIVATE, snippets = emptyList(), attachments = emptyList(),
            tags = emptyList(), occurredAt = null, projectBindingId = projectBindingId,
        ),
    ).id!!

    private fun newBinding(owner: Long, key: String): Long {
        val now = Instant.now()
        return bindings.save(
            ProjectBinding(
                ownerUserId = owner, projectKey = key, normalizedRepoId = null,
                storagePolicy = StoragePolicy.DEFAULT, createdAt = now, updatedAt = now,
            ),
        ).id!!
    }

    @Test
    fun `find_cases 는 본인 전체(미귀속 포함) 반환, query 필터, 타인 제외`() {
        val bindingId = newBinding(userId, "checkout")
        val bound = newCase(userId, "NPE in checkout", description = "null pointer", exceptionClass = "NullPointerException", projectBindingId = bindingId)
        val unbound = newCase(userId, "Timeout in worker", description = "deadline exceeded", exceptionClass = "TimeoutException")
        newCase(9999L, "someone else NPE", exceptionClass = "NullPointerException") // 타인

        // 바인딩 미지정 → 본인 전체(미귀속 unbound 포함), 타인 제외
        mvc.perform(get("/api/v1/agent/cases").with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.id == $bound)]").exists())
            .andExpect(jsonPath("$.items[?(@.id == $unbound)]").exists())
            .andExpect(jsonPath("$.items[?(@.title == 'someone else NPE')]").doesNotExist())

        // query=NullPointer → bound 만(예외클래스 매칭), unbound(Timeout) 제외
        mvc.perform(get("/api/v1/agent/cases").param("query", "nullpointer").with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.id == $bound)]").exists())
            .andExpect(jsonPath("$.items[?(@.id == $unbound)]").doesNotExist())

        // projectBindingId 필터 → bound 만
        mvc.perform(get("/api/v1/agent/cases").param("projectBindingId", "$bindingId").with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.id == $bound)]").exists())
            .andExpect(jsonPath("$.items[?(@.id == $unbound)]").doesNotExist())
    }

    @Test
    fun `타인 소유 projectBindingId 필터 → 404 PROJECT_BINDING_NOT_FOUND`() {
        val othersBinding = newBinding(8800L, "theirs")
        mvc.perform(get("/api/v1/agent/cases").param("projectBindingId", "$othersBinding").with(agent()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("PROJECT_BINDING_NOT_FOUND"))
    }

    @Test
    fun `get_case 는 미귀속 본인 Case 도 Step 본문·Solution 설명·검증까지 반환`() {
        val caseId = newCase(userId, "Flaky test", exceptionClass = "AssertionError") // 미귀속(null)
        val step = steps.save(
            Step.create(caseId, userId, orderIndex = 0, title = "retry with seed", status = StepStatus.RESOLVED,
                attemptType = "FIX", body = "fixed by pinning the RNG seed", insight = "order-dependent"),
        )
        caseSolutions.save(
            Solution.create(caseId, userId, title = "Pin RNG seed", stepIds = listOf(step.id!!),
                description = "set -Dseed=42",
                verification = SolutionVerification(result = "PASSED", executed = true, command = "./gradlew test", testId = "FlakyT")),
        )

        mvc.perform(get("/api/v1/agent/cases/$caseId").with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectBindingId").doesNotExist()) // 미귀속이어도 조회됨
            .andExpect(jsonPath("$.steps[0].body").value("fixed by pinning the RNG seed"))
            .andExpect(jsonPath("$.steps[0].insight").value("order-dependent"))
            .andExpect(jsonPath("$.solutions[0].description").value("set -Dseed=42"))
            .andExpect(jsonPath("$.solutions[0].verification.result").value("PASSED"))
            .andExpect(jsonPath("$.solutions[0].verification.command").value("./gradlew test"))
    }

    @Test
    fun `get_case 타인 소유 → 404 AGENT_CASE_NOT_FOUND`() {
        val othersCase = newCase(8800L, "theirs")
        mvc.perform(get("/api/v1/agent/cases/$othersCase").with(agent()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("AGENT_CASE_NOT_FOUND"))
    }
}
