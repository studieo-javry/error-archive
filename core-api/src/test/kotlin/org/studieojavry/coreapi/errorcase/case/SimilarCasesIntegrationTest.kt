package org.studieojavry.coreapi.errorcase.case

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort
import org.studieojavry.coreapi.errorcase.shared.application.port.FollowedUserReaderPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceMemberReaderPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * 유사 에러 추천 통합 — pg_trgm 은 H2 가 지원 안 하므로 **실 Postgres(Testcontainers)** 로만.
 * Flyway(V1~V3) 를 실 PG 에 돌려 스키마+pg_trgm 인덱스까지 함께 검증한다.
 *
 * 커버: 유사 랭킹(최상위가 가장 비슷한 케이스), 부분입력(null→NPE), 2자 미만 빈결과, limit,
 *       본인 스코프(타인 PUBLIC 케이스 비노출), 워크스페이스 비멤버 403, 무인증 401.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
])
class SimilarCasesIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    private val om = ObjectMapper()

    @MockitoBean private lateinit var authorSummaryReader: AuthorSummaryReaderPort
    @MockitoBean private lateinit var workspaceMemberReader: WorkspaceMemberReaderPort
    @MockitoBean private lateinit var followedUserReader: FollowedUserReaderPort
    // 멤버십 게이트 — iam REST 어댑터 대신 목으로(기본 null=비멤버). 403 경로 검증용.
    @MockitoBean private lateinit var workspaceQuery: WorkspaceQueryPort

    private lateinit var mvc: MockMvc
    private val me = 7L
    private val other = 8L

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    private fun createCase(uid: Long, title: String) {
        mvc.perform(
            post("/api/v1/error-cases").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("title" to title, "visibility" to "PUBLIC"))),
        ).andExpect(status().isCreated)
    }

    private fun seedMine() {
        createCase(me, "HikariCP 커넥션 풀 고갈로 간헐적 503 발생")
        createCase(me, "NullPointerException in OrderService.calculateTotal")
        createCase(me, "CORS preflight 403 on login")
        createCase(me, "OutOfMemoryError during PDF export")
    }

    @Test
    fun `유사도 상위는 가장 비슷한 케이스 — HikariCP 503`() {
        seedMine()
        mvc.perform(get("/api/v1/error-cases/similar").param("q", "HikariCP 503 커넥션 풀").with(asUser(me)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
            .andExpect(jsonPath("$.items[0].title").value(org.hamcrest.Matchers.containsString("HikariCP")))
            .andExpect(jsonPath("$.items[0].score").isNumber)
    }

    @Test
    fun `부분 입력 null 은 NPE 케이스를 찾는다`() {
        seedMine()
        mvc.perform(get("/api/v1/error-cases/similar").param("q", "NullPointer").with(asUser(me)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].title").value(org.hamcrest.Matchers.containsString("NullPointer")))
    }

    @Test
    fun `2자 미만은 빈 목록`() {
        seedMine()
        mvc.perform(get("/api/v1/error-cases/similar").param("q", "H").with(asUser(me)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
    }

    @Test
    fun `limit 는 반환 수를 제한한다`() {
        seedMine()
        mvc.perform(get("/api/v1/error-cases/similar").param("q", "error").param("limit", "1").with(asUser(me)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(1)))
    }

    @Test
    fun `본인 스코프 — 타인의 PUBLIC 케이스는 안 나온다`() {
        createCase(other, "HikariCP 커넥션 풀 고갈로 간헐적 503 발생")  // 남의 케이스
        mvc.perform(get("/api/v1/error-cases/similar").param("q", "HikariCP 503").with(asUser(me)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
    }

    @Test
    fun `워크스페이스 비멤버는 403`() {
        // workspaceQuery 목이 기본 null(비멤버) 반환 → 게이트에서 거부.
        mvc.perform(get("/api/v1/error-cases/similar").param("q", "anything").param("workspaceId", "99").with(asUser(me)))
            .andExpect(status().isForbidden)
    }
}
