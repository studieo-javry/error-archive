package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Agent Capture(P0) PR3 근거 첨부 — Testcontainers + validate(V20 정합 증명). 스토리지는 목.
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
class AgentCaptureArtifactIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var om: ObjectMapper
    @MockitoBean private lateinit var storage: AttachmentStoragePort
    private lateinit var mvc: MockMvc

    private val userId = 6001L
    private val connectionId = UUID.randomUUID().toString()
    private val scopes = listOf("capture:read", "capture:write", "artifact:write")

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun agent() = authentication(
        InternalAuthentication(userId, scopes.map { SimpleGrantedAuthority("SCOPE_$it") }, "gateway", connectionId, scopes),
    )

    private fun newCapture(key: String): UUID {
        val uuid = UUID.randomUUID()
        mvc.perform(
            post("/api/v1/agent/captures").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("captureUuid" to uuid.toString(), "title" to "t"))),
        ).andExpect(status().isCreated)
        return uuid
    }

    private fun upload(uuid: UUID, clientArtifactId: String, bytes: ByteArray, contentType: String) =
        mvc.perform(
            multipart("/api/v1/agent/captures/$uuid/artifacts")
                .file(MockMultipartFile("file", "f", contentType, bytes))
                .param("clientArtifactId", clientArtifactId)
                .with(agent()),
        )

    @Test
    fun `PNG 업로드 후 상세에 보이고, 같은 id-같은 내용은 멱등, 다른 내용은 409`() {
        val uuid = newCapture("c-art")

        upload(uuid, "shot1", PNG, "image/png")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.clientArtifactId").value("shot1"))

        mvc.perform(get("/api/v1/agent/captures/$uuid").with(agent()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.artifacts.length()").value(1))

        // 같은 clientArtifactId + 같은 바이트 → 멱등(200)
        upload(uuid, "shot1", PNG, "image/png").andExpect(status().isOk)

        // 같은 clientArtifactId + 다른 바이트 → 409
        upload(uuid, "shot1", PNG + byteArrayOf(9), "image/png")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ARTIFACT_HASH_MISMATCH"))
    }

    @Test
    fun `허용되지 않은 형식은 415`() {
        val uuid = newCapture("c-bad")
        // SVG(마크업)을 text/plain 으로 위장 → 거부
        upload(uuid, "svg", "<svg xmlns='x'></svg>".toByteArray(), "text/plain")
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_ARTIFACT"))
        // PNG 매직 불일치(이름만 png)
        upload(uuid, "fake", "not a png".toByteArray(), "image/png")
            .andExpect(status().isUnsupportedMediaType)
    }

    @Test
    fun `파일 크기 초과는 413`() {
        val uuid = newCapture("c-big")
        val tooBig = ByteArray(10 * 1024 * 1024 + 1)
        upload(uuid, "big", tooBig, "text/plain")
            .andExpect(status().isPayloadTooLarge)
            .andExpect(jsonPath("$.code").value("ARTIFACT_TOO_LARGE"))
    }

    @Test
    fun `참조 근거 미업로드면 complete 는 422, 업로드 후 봉인된다`() {
        val uuid = newCapture("c-ev")
        // evidenceRefs 로 아직 없는 근거를 참조한 Attempt
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/attempts").with(agent())
                .header("Idempotency-Key", "c-ev-a1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "clientAttemptId" to "a1", "result" to "FAILED",
                    "detail" to mapOf("title" to "t", "evidenceRefs" to listOf("log1")),
                ))),
        ).andExpect(status().isCreated)

        mvc.perform(
            post("/api/v1/agent/captures/$uuid/complete").with(agent())
                .header("Idempotency-Key", "c-ev-done").contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("EVIDENCE_NOT_READY"))

        // 근거 업로드 후 봉인 성공
        upload(uuid, "log1", "{\"k\":1}".toByteArray(), "application/json").andExpect(status().isCreated)
        mvc.perform(
            post("/api/v1/agent/captures/$uuid/complete").with(agent())
                .header("Idempotency-Key", "c-ev-done2").contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andExpect(status().isOk).andExpect(jsonPath("$.status").value("READY_FOR_REVIEW"))
    }

    @Test
    fun `artifact-write scope 없으면 403`() {
        val uuid = newCapture("c-nos")
        mvc.perform(
            multipart("/api/v1/agent/captures/$uuid/artifacts")
                .file(MockMultipartFile("file", "f", "image/png", PNG))
                .param("clientArtifactId", "x")
                .with(authentication(InternalAuthentication(
                    userId,
                    listOf(SimpleGrantedAuthority("SCOPE_capture:write")),
                    "gateway", connectionId, listOf("capture:read", "capture:write"),
                ))),
        ).andExpect(status().isForbidden)
    }
}
