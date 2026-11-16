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

/**
 * 진행 중(OPEN) Capture 후보 조회(GET /api/v1/agent/captures) — 자동기록(ARC)에서 AI 가 같은 버그를
 * 재사용하고 다른 버그는 분리하도록 돕는 조회. OPEN 만·봉인(READY_FOR_REVIEW) 제외, sessionUuid 로 좁히기,
 * 연결(install) 격리를 검증한다.
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
class AgentOpenCaptureListIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var om: ObjectMapper
    private lateinit var mvc: MockMvc

    private val userId = 9400L
    private val conn = UUID.randomUUID().toString()
    private val scopes = listOf("capture:read", "capture:write")

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun agent(c: String = conn) =
        authentication(InternalAuthentication(userId, scopes.map { SimpleGrantedAuthority("SCOPE_$it") }, "gateway", c, scopes))

    private fun registerSession(sessionUuid: UUID, key: String, c: String = conn) =
        mvc.perform(
            post("/api/v1/agent/sessions").with(agent(c))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "sessionUuid" to sessionUuid.toString(),
                    "clientKind" to "claude-code",
                    "clientSessionId" to sessionUuid.toString(),
                ))),
        ).andExpect(status().isCreated)

    private fun createCapture(uuid: UUID, key: String, sessionUuid: UUID? = null, c: String = conn) {
        val body = mutableMapOf<String, Any>("captureUuid" to uuid.toString(), "title" to "bug $key")
        if (sessionUuid != null) body["sessionUuid"] = sessionUuid.toString()
        mvc.perform(
            post("/api/v1/agent/captures").with(agent(c))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)),
        ).andExpect(status().isCreated)
    }

    private fun complete(uuid: UUID, key: String) =
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/complete").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andExpect(status().isOk)

    @Test
    fun `OPEN 만 반환하고 봉인된 초안은 제외한다`() {
        val open = UUID.randomUUID()
        val sealed = UUID.randomUUID()
        createCapture(open, "ol-open")
        createCapture(sealed, "ol-sealed")
        complete(sealed, "ol-sealed-c")

        mvc.perform(get("/api/v1/agent/captures").with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].captureUuid").value(open.toString()))
            .andExpect(jsonPath("$.items[0].status").value("OPEN"))
    }

    @Test
    fun `sessionUuid 를 주면 그 세션에 연결된 OPEN 만 반환한다`() {
        val sid = UUID.randomUUID()
        registerSession(sid, "ol-sess")
        val inSession = UUID.randomUUID()
        val noSession = UUID.randomUUID()
        createCapture(inSession, "ol-in", sessionUuid = sid)
        createCapture(noSession, "ol-no")

        mvc.perform(get("/api/v1/agent/captures").param("sessionUuid", sid.toString()).with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].captureUuid").value(inSession.toString()))
    }

    @Test
    fun `다른 연결이 만든 OPEN 초안은 보이지 않는다`() {
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        val otherConn = UUID.randomUUID().toString()
        createCapture(mine, "ol-mine")
        createCapture(other, "ol-other", c = otherConn)

        mvc.perform(get("/api/v1/agent/captures").with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].captureUuid").value(mine.toString()))
    }

    @Test
    fun `다른 연결의 세션 uuid 로는 좁혀지지 않아 빈 목록`() {
        val sid = UUID.randomUUID()
        val otherConn = UUID.randomUUID().toString()
        registerSession(sid, "ol-xsess", c = otherConn)
        createCapture(UUID.randomUUID(), "ol-mine2")

        mvc.perform(get("/api/v1/agent/captures").param("sessionUuid", sid.toString()).with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))
    }
}
