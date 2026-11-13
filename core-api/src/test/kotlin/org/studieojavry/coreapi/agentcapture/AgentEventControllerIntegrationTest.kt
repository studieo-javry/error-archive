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

/** P1-1 — Hook 이벤트 배치 적재(POST /api/v1/agent/events/batch). Testcontainers. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
])
class AgentEventControllerIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var om: ObjectMapper
    private lateinit var mvc: MockMvc

    private val userId = 9200L
    private val conn = UUID.randomUUID().toString()

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun agent(scopes: List<String> = listOf("capture:read", "capture:write")) =
        authentication(InternalAuthentication(userId, scopes.map { SimpleGrantedAuthority("SCOPE_$it") }, "gateway", conn, scopes))

    private fun event(id: String, payload: Map<String, Any?>) = mapOf(
        "clientEventId" to id, "eventType" to "PostToolUse", "localSequence" to 1,
        "observedAt" to "2026-11-10T00:00:00Z", "redactedPayload" to payload,
    )

    private fun batch(vararg events: Map<String, Any?>) =
        post("/api/v1/agent/events/batch").with(agent())
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(mapOf("events" to events.toList())))

    @Test
    fun `배치 적재 ACCEPTED, 같은 payload 재전송 DUPLICATE, 다른 payload 충돌 REJECTED`() {
        mvc.perform(batch(event("e1", mapOf("tool" to "bash")), event("e2", mapOf("tool" to "edit"))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.results.length()").value(2))
            .andExpect(jsonPath("$.results[0].result").value("ACCEPTED"))
            .andExpect(jsonPath("$.results[1].result").value("ACCEPTED"))

        // 같은 clientEventId + 같은 payload → DUPLICATE
        mvc.perform(batch(event("e1", mapOf("tool" to "bash"))))
            .andExpect(status().isOk).andExpect(jsonPath("$.results[0].result").value("DUPLICATE"))

        // 같은 clientEventId + 다른 payload → REJECTED(충돌)
        mvc.perform(batch(event("e1", mapOf("tool" to "DIFFERENT"))))
            .andExpect(status().isOk).andExpect(jsonPath("$.results[0].result").value("REJECTED"))
    }

    @Test
    fun `payload 64KiB 초과는 REJECTED`() {
        val big = "x".repeat(70_000)
        mvc.perform(batch(event("e-big", mapOf("blob" to big))))
            .andExpect(status().isOk).andExpect(jsonPath("$.results[0].result").value("REJECTED"))
    }

    @Test
    fun `빈 배치는 400`() {
        mvc.perform(
            post("/api/v1/agent/events/batch").with(agent())
                .contentType(MediaType.APPLICATION_JSON).content("""{"events":[]}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `capture-write scope 없으면 403`() {
        mvc.perform(
            post("/api/v1/agent/events/batch").with(agent(scopes = listOf("capture:read")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("events" to listOf(event("e1", mapOf("a" to 1)))))),
        ).andExpect(status().isForbidden)
    }

    private fun eventWithSession(id: String, session: String) = mapOf(
        "clientEventId" to id, "eventType" to "PostToolUse", "localSequence" to 1,
        "observedAt" to "2026-11-10T00:00:00Z", "redactedPayload" to mapOf("tool" to "bash"), "sessionId" to session,
    )

    @Test
    fun `미분류 조회 — 본인 것만·sessionId 필터·타인 제외`() {
        mvc.perform(
            post("/api/v1/agent/events/batch").with(agent())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("events" to listOf(
                    eventWithSession("o1", "s1"), eventWithSession("o2", "s1"), eventWithSession("o3", "s2"),
                )))),
        ).andExpect(status().isOk)

        // 타인 이벤트
        val other = authentication(
            InternalAuthentication(5555L, listOf(SimpleGrantedAuthority("SCOPE_capture:write")), "gateway", UUID.randomUUID().toString(), listOf("capture:write")),
        )
        mvc.perform(
            post("/api/v1/agent/events/batch").with(other)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("events" to listOf(eventWithSession("x1", "s1"))))),
        ).andExpect(status().isOk)

        // 전체(본인) → o1·o3 포함, 타인 x1 제외
        mvc.perform(get("/api/v1/agent/events").with(agent(listOf("capture:read"))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.clientEventId=='o1')]").exists())
            .andExpect(jsonPath("$.items[?(@.clientEventId=='o3')]").exists())
            .andExpect(jsonPath("$.items[?(@.clientEventId=='x1')]").doesNotExist())

        // sessionId=s1 → o1 포함, o3(s2) 제외
        mvc.perform(get("/api/v1/agent/events").with(agent(listOf("capture:read"))).queryParam("sessionId", "s1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.clientEventId=='o1')]").exists())
            .andExpect(jsonPath("$.items[?(@.clientEventId=='o3')]").doesNotExist())
    }

    @Test
    fun `조회는 capture-read 필요 — 없으면 403`() {
        mvc.perform(get("/api/v1/agent/events").with(agent(listOf("capture:write"))))
            .andExpect(status().isForbidden)
    }
}
