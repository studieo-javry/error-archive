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
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/** P1-0 증분3 — 에이전트 case:read(/api/v1/agent/cases). 본인 소유 + 바인딩 귀속만. Testcontainers. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
])
class AgentCaseControllerIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var errorCases: ErrorCaseRepositoryPort
    @Autowired private lateinit var steps: StepRepositoryPort
    @Autowired private lateinit var caseSolutions: SolutionRepositoryPort
    @Autowired private lateinit var projectBindings: ProjectBindingRepositoryPort
    private lateinit var mvc: MockMvc

    private val userId = 9100L
    private val conn = java.util.UUID.randomUUID().toString()

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun agent(uid: Long = userId, scopes: List<String> = listOf("case:read")) =
        authentication(
            InternalAuthentication(uid, scopes.map { SimpleGrantedAuthority("SCOPE_$it") }, "gateway", conn, scopes),
        )

    private fun seedBoundCase(owner: Long, bindingId: Long?, title: String): Long {
        val case = errorCases.save(
            ErrorCase.create(
                ownerUserId = owner,
                title = title,
                project = "checkout",
                snapshot = null,
                description = "d",
                meta = Meta.create(workspaceId = null, severityCode = null),
                visibility = Visibility.PRIVATE,
                snippets = emptyList(),
                attachments = emptyList(),
                occurredAt = null,
                projectBindingId = bindingId,
            ),
        )
        val caseId = case.id!!
        val step = steps.save(
            Step.create(errorCaseId = caseId, authorUserId = owner, orderIndex = 0, title = "fix", status = StepStatus.RESOLVED,
                attemptType = null, body = null, insight = null),
        )
        caseSolutions.save(Solution.create(errorCaseId = caseId, authorUserId = owner, title = "guard", stepIds = listOf(step.id!!)))
        return caseId
    }

    private fun binding(owner: Long, key: String): Long =
        projectBindings.save(ProjectBinding(ownerUserId = owner, projectKey = key, createdAt = Instant.now(), updatedAt = Instant.now())).id!!

    @Test
    fun `바인딩 귀속 케이스만 검색·상세, 미귀속·타인은 제외`() {
        val b = binding(userId, "checkout")
        val bound = seedBoundCase(userId, b, "Bound case")
        val unbound = seedBoundCase(userId, null, "Unbound case")
        val otherUser = seedBoundCase(7777L, binding(7777L, "x"), "Other user case")

        // 검색: 귀속 케이스만(미귀속·타인 제외)
        mvc.perform(get("/api/v1/agent/cases").with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(bound))

        // 상세: 귀속 케이스 — Step·Solution 포함
        mvc.perform(get("/api/v1/agent/cases/$bound").with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(bound))
            .andExpect(jsonPath("$.projectBindingId").value(b))
            .andExpect(jsonPath("$.steps.length()").value(1))
            .andExpect(jsonPath("$.solutions.length()").value(1))

        // 미귀속 상세 → 404
        mvc.perform(get("/api/v1/agent/cases/$unbound").with(agent()))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("AGENT_CASE_NOT_FOUND"))

        // 타인 케이스 상세 → 404
        mvc.perform(get("/api/v1/agent/cases/$otherUser").with(agent()))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("AGENT_CASE_NOT_FOUND"))
    }

    @Test
    fun `case-read scope 없으면 403`() {
        mvc.perform(get("/api/v1/agent/cases").with(agent(scopes = listOf("capture:read"))))
            .andExpect(status().isForbidden)
    }
}
