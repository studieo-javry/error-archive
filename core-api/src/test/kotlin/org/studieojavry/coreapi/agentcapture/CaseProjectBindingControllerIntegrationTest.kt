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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/**
 * 기존 Case 를 ProjectBinding 에 사후 귀속/해제(PUT·DELETE /api/v1/me/cases/{id}/project-binding).
 * #162 가 import 신규 Case 만 귀속시킨 공백(기존 Case 연계)을 메운다. Testcontainers.
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
class CaseProjectBindingControllerIntegrationTest {

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var errorCases: ErrorCaseRepositoryPort
    @Autowired private lateinit var bindings: ProjectBindingRepositoryPort
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    private fun newCase(owner: Long): Long = errorCases.save(
        ErrorCase.create(
            ownerUserId = owner, title = "t", project = null, snapshot = null, description = null,
            meta = Meta.create(workspaceId = null, severityCode = null),
            visibility = Visibility.PRIVATE, snippets = emptyList(), attachments = emptyList(),
            tags = emptyList(), occurredAt = null,
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

    private fun bind(uid: Long, caseId: Long, bindingId: Long) =
        put("/api/v1/me/cases/$caseId/project-binding").with(asUser(uid))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"projectBindingId":$bindingId}""")

    @Test
    fun `기존 Case 귀속(204) → case_read 대상에 포함, 해제(204) → 제외`() {
        val uid = 9200L
        val caseId = newCase(uid)
        val bindingId = newBinding(uid, "checkout-svc")

        // 귀속 전: 미귀속이라 해당 바인딩 필터 조회엔 안 보임
        assertTrue(errorCases.searchOwnedCasesForAgent(uid, null, bindingId, 50).none { it.id == caseId })

        // 귀속 → 204
        mvc.perform(bind(uid, caseId, bindingId)).andExpect(status().isNoContent)

        // 이제 바인딩 귀속 → 해당 바인딩 필터 조회에 포함
        assertEquals(listOf(caseId), errorCases.searchOwnedCasesForAgent(uid, null, bindingId, 50).map { it.id })

        // 해제 → 204 → 다시 제외(바인딩 필터 기준)
        mvc.perform(delete("/api/v1/me/cases/$caseId/project-binding").with(asUser(uid)))
            .andExpect(status().isNoContent)
        assertTrue(errorCases.searchOwnedCasesForAgent(uid, null, bindingId, 50).none { it.id == caseId })
    }

    @Test
    fun `타인 소유 Case 귀속 → 404 CASE_NOT_FOUND`() {
        val owner = 9210L
        val other = 9211L
        val caseId = newCase(owner)
        val bindingId = newBinding(other, "svc")
        mvc.perform(bind(other, caseId, bindingId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"))
    }

    @Test
    fun `타인 소유(또는 없는) ProjectBinding 에 귀속 → 404 PROJECT_BINDING_NOT_FOUND`() {
        val uid = 9220L
        val caseId = newCase(uid)
        val othersBinding = newBinding(9221L, "svc")
        mvc.perform(bind(uid, caseId, othersBinding))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("PROJECT_BINDING_NOT_FOUND"))
    }
}
