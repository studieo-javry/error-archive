package org.studieojavry.coreapi.agentcapture

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
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper

/** P1-0 증분1 — 프로젝트 바인딩 관리 API(/api/v1/me/project-bindings). Testcontainers. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
])
class ProjectBindingControllerIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var om: ObjectMapper
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    @Test
    fun `바인딩 생성(201)·중복 멱등(200)·소유자별 목록`() {
        val uid = 8100L
        mvc.perform(
            post("/api/v1/me/project-bindings").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("projectKey" to "checkout-svc", "normalizedRepoId" to "github:acme/checkout"))),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.projectKey").value("checkout-svc"))
            .andExpect(jsonPath("$.normalizedRepoId").value("github:acme/checkout"))
            .andExpect(jsonPath("$.id").isNumber)

        // 같은 projectKey 재생성 → 기존 반환(200)
        mvc.perform(
            post("/api/v1/me/project-bindings").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("projectKey" to "checkout-svc"))),
        ).andExpect(status().isOk).andExpect(jsonPath("$.projectKey").value("checkout-svc"))

        // 목록: 본인 1건
        mvc.perform(get("/api/v1/me/project-bindings").with(asUser(uid)))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))

        // 타 사용자는 분리(빈 목록)
        mvc.perform(get("/api/v1/me/project-bindings").with(asUser(9999L)))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(0))
    }

    @Test
    fun `projectKey 빈 값은 400`() {
        mvc.perform(
            post("/api/v1/me/project-bindings").with(asUser(8101L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("projectKey" to ""))),
        ).andExpect(status().isBadRequest)
    }
}
