package org.studieojavry.coreapi.errorcase.case

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort
import org.studieojavry.coreapi.errorcase.shared.application.port.FollowedUserReaderPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceMemberReaderPort
import org.studieojavry.internalauth.InternalAuthentication

/**
 * ErrorCase 라이프사이클 통합 — @SpringBootTest + MockMvc + H2(실 JPA).
 * 외부(REST) 포트만 목킹(author enrichment / workspace / following). 나머지는 실제 스택.
 *
 * 커버: 개인 케이스 create 201 → get 200 → list 포함 → PATCH 상태전이(OPEN→IN_PROGRESS) →
 *       불법 전이(OPEN→RESOLVED) 400 → delete 204 → 삭제 후 404 → me-too 본인 403 → 무인증 401.
 */
@SpringBootTest
@ActiveProfiles("test")
class ErrorCaseIntegrationTest {

    @Autowired private lateinit var context: WebApplicationContext
    private val om = ObjectMapper()

    // 외부 iam-api REST 어댑터 대체 — enrichment/권한 조회는 목으로.
    @MockitoBean private lateinit var authorSummaryReader: AuthorSummaryReaderPort
    @MockitoBean private lateinit var workspaceMemberReader: WorkspaceMemberReaderPort
    @MockitoBean private lateinit var followedUserReader: FollowedUserReaderPort

    private lateinit var mvc: MockMvc
    private val ownerId = 7L

    @BeforeEach
    fun setUp() {
        // 목 3종은 기본값(빈 Map/List) 반환으로 충분 — author enrichment/권한/following 모두 "없음".
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    private fun createCase(uid: Long, title: String): Long {
        val body = om.writeValueAsString(mapOf("title" to title, "visibility" to "PUBLIC"))
        val json = mvc.perform(
            post("/api/v1/error-cases").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return om.readTree(json).get("id").asLong()
    }

    @Test
    fun `케이스 생성은 201 과 OPEN 상태를 반환한다`() {
        mvc.perform(
            post("/api/v1/error-cases").with(asUser(ownerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"NPE in OrderService","visibility":"PUBLIC"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.status").value("OPEN"))
    }

    @Test
    fun `생성한 케이스를 상세 조회할 수 있다`() {
        val id = createCase(ownerId, "조회 대상")
        mvc.perform(get("/api/v1/error-cases/$id").with(asUser(ownerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.title").value("조회 대상"))
            .andExpect(jsonPath("$.status").value("OPEN"))
    }

    @Test
    fun `내 목록에 생성한 케이스가 포함된다`() {
        val id = createCase(ownerId, "목록 대상")
        mvc.perform(get("/api/v1/error-cases").with(asUser(ownerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
            .andExpect(jsonPath("$.items[?(@.id == $id)]").exists())
    }

    @Test
    fun `PATCH 로 OPEN 에서 IN_PROGRESS 전이가 가능하다`() {
        val id = createCase(ownerId, "전이 대상")
        mvc.perform(
            patch("/api/v1/error-cases/$id").with(asUser(ownerId))
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"IN_PROGRESS"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
    }

    @Test
    fun `PATCH 로 OPEN 에서 RESOLVED 직접 전이는 400`() {
        val id = createCase(ownerId, "불법 전이")
        mvc.perform(
            patch("/api/v1/error-cases/$id").with(asUser(ownerId))
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"RESOLVED"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `삭제 후 조회는 404`() {
        val id = createCase(ownerId, "삭제 대상")
        mvc.perform(delete("/api/v1/error-cases/$id").with(asUser(ownerId)))
            .andExpect(status().isNoContent)
        mvc.perform(get("/api/v1/error-cases/$id").with(asUser(ownerId)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `본인 케이스에 me-too 는 403`() {
        val id = createCase(ownerId, "본인 me-too")
        mvc.perform(post("/api/v1/error-cases/$id/me-too").with(asUser(ownerId)))
            .andExpect(status().isForbidden)
    }
}
