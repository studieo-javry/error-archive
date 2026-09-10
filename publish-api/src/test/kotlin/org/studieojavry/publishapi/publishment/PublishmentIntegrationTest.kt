package org.studieojavry.publishapi.publishment

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anySet
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.internalauth.InternalAuthentication
import org.studieojavry.publishapi.publishment.application.port.CoreCaseDataPort
import org.studieojavry.publishapi.publishment.application.port.CoreCaseFullData
import org.studieojavry.publishapi.publishment.application.port.OriginalCaseStatusReaderPort
import org.studieojavry.publishapi.publishment.infrastructure.attachment.AttachmentPresigner
import java.time.LocalDateTime

/**
 * publish-api 통합 검증 — @SpringBootTest + MockMvc + H2(실 JPA/멱등 persist).
 * 외부 의존(core full-data / status / S3 presign)만 목킹하고 나머지는 실제 스택을 태운다.
 *
 * 커버:
 *  - 발행 201 + 멱등 재생 200(같은 키·본문) + 멱등 충돌 409(같은 키·다른 본문)
 *  - 케이스당 1 canonical: 이미 발행된 케이스 재발행 → 409 + existingSlug
 *  - by-case: 소유자 200 / 타 사용자 404
 *  - unpublish → 공개 페이지 410 → restore → 공개 페이지 200
 *  - 선별 step 0개 → 422
 *  - 인증 없음 → 401
 *  - 공개 페이지 HTML 200
 */
@SpringBootTest
@ActiveProfiles("test")
class PublishmentIntegrationTest {

    @Autowired private lateinit var context: WebApplicationContext
    private val objectMapper = ObjectMapper()

    @MockitoBean private lateinit var coreCaseData: CoreCaseDataPort
    @MockitoBean private lateinit var statusReader: OriginalCaseStatusReaderPort
    @MockitoBean private lateinit var attachmentPresigner: AttachmentPresigner

    private lateinit var mvc: MockMvc

    private val ownerId = 7L

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
        // 어떤 caseId 든 step 1개짜리 유효 케이스로 응답 (owner = ownerId).
        given(coreCaseData.fetchFullData(anyLong())).willAnswer { inv ->
            fullDataFor(inv.arguments[0] as Long)
        }
        given(statusReader.fetchStatuses(anySet())).willReturn(null) // stale 판정 skip
    }

    private fun fullDataFor(caseId: Long) = CoreCaseFullData(
        id = caseId, ownerUserId = ownerId, title = "케이스 $caseId", description = "본문",
        tags = listOf("kotlin"), status = "OPEN", visibility = "PUBLIC",
        createdAt = LocalDateTime.of(2026, 1, 1, 9, 0), occurredAt = null, snapshot = null,
        steps = listOf(
            CoreCaseFullData.StepData(
                id = 1L, orderIndex = 0, title = "재현", body = "step body",
                insight = null, status = "SUCCESS", attemptType = null,
                createdAt = LocalDateTime.of(2026, 1, 1, 9, 0),
            ),
        ),
        solutions = emptyList(), snippets = emptyList(), attachments = emptyList(),
    )

    private fun asUser(userId: Long) =
        authentication(InternalAuthentication(userId, emptyList(), "test-caller"))

    private fun publish(userId: Long, caseId: Long, idemKey: String, title: String? = null) =
        post("/api/v1/publishments")
            .with(asUser(userId))
            .header("Idempotency-Key", idemKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    buildMap<String, Any> {
                        put("caseId", caseId)
                        if (title != null) put("title", title)
                    },
                ),
            )

    // ── 발행 + 멱등성 ────────────────────────────────────────────────

    @Test
    fun `발행은 201 을 반환하고 slug 를 준다`() {
        mvc.perform(publish(ownerId, caseId = 1001L, idemKey = "k-1001"))
            .andExpect(status().isCreated)
            .andExpect(header().string("Idempotent-Replayed", "false"))
            .andExpect(jsonPath("$.slug").isNotEmpty)
            .andExpect(jsonPath("$.status").value("LIVE"))
            .andExpect(jsonPath("$.stepCount").value(1))
    }

    @Test
    fun `같은 키·같은 본문 재요청은 200 으로 재생된다`() {
        mvc.perform(publish(ownerId, caseId = 1009L, idemKey = "k-1009")).andExpect(status().isCreated)
        mvc.perform(publish(ownerId, caseId = 1009L, idemKey = "k-1009"))
            .andExpect(status().isOk)
            .andExpect(header().string("Idempotent-Replayed", "true"))
    }

    @Test
    fun `같은 키·다른 본문은 409 충돌`() {
        mvc.perform(publish(ownerId, caseId = 1002L, idemKey = "k-1002", title = "제목A"))
            .andExpect(status().isCreated)
        mvc.perform(publish(ownerId, caseId = 1002L, idemKey = "k-1002", title = "제목B"))
            .andExpect(status().isConflict)
    }

    @Test
    fun `이미 발행된 케이스 재발행은 409 + existingSlug`() {
        mvc.perform(publish(ownerId, caseId = 1003L, idemKey = "k-1003a")).andExpect(status().isCreated)
        mvc.perform(publish(ownerId, caseId = 1003L, idemKey = "k-1003b"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.existingSlug").isNotEmpty)
            .andExpect(jsonPath("$.code").value("PUBLISHMENT_ALREADY_EXISTS"))
    }

    @Test
    fun `선별된 step 이 0개면 422`() {
        val body = objectMapper.writeValueAsString(mapOf("caseId" to 1006L, "includeStepIds" to listOf(42L)))
        mvc.perform(
            post("/api/v1/publishments").with(asUser(ownerId))
                .header("Idempotency-Key", "k-1006")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `인증 없이 발행하면 401`() {
        mvc.perform(
            post("/api/v1/publishments").header("Idempotency-Key", "k-x")
                .contentType(MediaType.APPLICATION_JSON).content("""{"caseId":1007}"""),
        ).andExpect(status().isUnauthorized)
    }

    // ── by-case 소유권 ───────────────────────────────────────────────

    @Test
    fun `by-case 는 소유자에게 발행물, 타 사용자에게 404`() {
        mvc.perform(publish(ownerId, caseId = 1004L, idemKey = "k-1004")).andExpect(status().isCreated)

        mvc.perform(get("/api/v1/publishments/by-case/1004").with(asUser(ownerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.originalCaseId").value(1004))

        mvc.perform(get("/api/v1/publishments/by-case/1004").with(asUser(999L)))
            .andExpect(status().isNotFound)
    }

    // ── 공개 페이지 라이프사이클 ──────────────────────────────────────

    @Test
    fun `발행 → 공개페이지 200, unpublish → 410, restore → 200`() {
        val slug = objectMapper.readTree(
            mvc.perform(publish(ownerId, caseId = 1005L, idemKey = "k-1005"))
                .andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("slug").asText()

        mvc.perform(get("/p/$slug"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))

        mvc.perform(post("/api/v1/publishments/by-slug/$slug/unpublish").with(asUser(ownerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UNPUBLISHED"))

        // 공개 라우트는 viewer=null → 내려진 발행물은 410 Gone
        mvc.perform(get("/p/$slug")).andExpect(status().isGone)

        mvc.perform(post("/api/v1/publishments/by-slug/$slug/publish").with(asUser(ownerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LIVE"))

        mvc.perform(get("/p/$slug")).andExpect(status().isOk)
    }

    // ── 내 목록 ─────────────────────────────────────────────────────

    @Test
    fun `내 목록은 발행물을 포함한다`() {
        mvc.perform(publish(ownerId, caseId = 1008L, idemKey = "k-1008")).andExpect(status().isCreated)
        mvc.perform(get("/api/v1/publishments/me").with(asUser(ownerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
            .andExpect(jsonPath("$.items[0].slug").isNotEmpty)
    }
}
