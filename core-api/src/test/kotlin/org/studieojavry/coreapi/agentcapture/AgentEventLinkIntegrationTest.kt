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
 * P1-2 — 미분류 Hook 이벤트 → OPEN 초안 연결(POST /api/v1/agent/captures/{uuid}/events).
 * 멱등 재연결, 미존재 이벤트 skip, 타 초안 소속 이벤트 가로채지 않음, 봉인 초안 409 를 검증.
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
class AgentEventLinkIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var om: ObjectMapper
    private lateinit var mvc: MockMvc

    private val userId = 9300L
    private val conn = UUID.randomUUID().toString()
    private val scopes = listOf("capture:read", "capture:write")

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun agent(c: String = conn) =
        authentication(InternalAuthentication(userId, scopes.map { SimpleGrantedAuthority("SCOPE_$it") }, "gateway", c, scopes))

    private fun ingest(vararg ids: String, c: String = conn) {
        val events = ids.map { mapOf(
            "clientEventId" to it, "eventType" to "PostToolUse", "localSequence" to 1,
            "observedAt" to "2026-11-11T00:00:00Z", "redactedPayload" to mapOf("tool" to "bash"),
        ) }
        mvc.perform(
            post("/api/v1/agent/events/batch").with(agent(c))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("events" to events))),
        ).andExpect(status().isOk)
    }

    private fun createCapture(uuid: UUID, key: String) =
        mvc.perform(
            post("/api/v1/agent/captures").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("captureUuid" to uuid.toString(), "title" to "NPE"))),
        ).andExpect(status().isCreated)

    private fun link(uuid: UUID, vararg ids: String) =
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/events").with(agent())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("clientEventIds" to ids.toList()))),
        )

    @Test
    fun `미분류 이벤트 연결, 재연결 멱등, 미존재는 skip`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "lk-c1")
        ingest("e1", "e2")

        // e1,e2 연결 + 없는 ghost 는 skip
        link(uuid, "e1", "e2", "ghost")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.linked.length()").value(2))
            .andExpect(jsonPath("$.skipped.length()").value(1))
            .andExpect(jsonPath("$.skipped[0]").value("ghost"))

        // 같은 초안으로 재연결 → 멱등(linked 유지, 중복 없음)
        link(uuid, "e1", "e2")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.linked.length()").value(2))
            .andExpect(jsonPath("$.skipped.length()").value(0))
    }

    @Test
    fun `다른 초안에 묶인 이벤트는 가로채지 않고 skip`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        createCapture(a, "lk-a")
        createCapture(b, "lk-b")
        ingest("x1")

        link(a, "x1").andExpect(status().isOk).andExpect(jsonPath("$.linked[0]").value("x1"))
        // 이미 a 에 묶였으므로 b 는 가로채지 못함 → skip
        link(b, "x1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.linked.length()").value(0))
            .andExpect(jsonPath("$.skipped[0]").value("x1"))
    }

    @Test
    fun `봉인된 초안에 연결하면 409 CAPTURE_SEALED`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "lk-sealed")
        ingest("s1")
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/complete").with(agent())
                .header("Idempotency-Key", "lk-sealed-complete")
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andExpect(status().isOk)

        link(uuid, "s1")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CAPTURE_SEALED"))
    }

    @Test
    fun `타인 소유 초안 연결은 404`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "lk-owner")
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/events")
                .with(authentication(InternalAuthentication(99999L, scopes.map { SimpleGrantedAuthority("SCOPE_$it") }, "gateway", UUID.randomUUID().toString(), scopes)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("clientEventIds" to listOf("z1")))),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `빈 목록은 400`() {
        val uuid = UUID.randomUUID()
        createCapture(uuid, "lk-empty")
        link(uuid).andExpect(status().isBadRequest)
    }
}
