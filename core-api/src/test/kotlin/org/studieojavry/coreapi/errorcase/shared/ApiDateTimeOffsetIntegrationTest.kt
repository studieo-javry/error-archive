package org.studieojavry.coreapi.errorcase.shared

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
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
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 공개 API 시각 응답은 **오프셋 포함**(예: `2026-10-30T19:00:00+09:00`)이어야 한다.
 *
 * stg 와 같은 조건 — JVM 기본 시간대 KST(Dockerfile `TZ=Asia/Seoul`), Postgres 세션 UTC — 에서
 * `timestamp without time zone` 컬럼은 UTC 벽시계로 저장되고, 읽을 때 JVM 시간대(KST) 벽시계로 돌아온다.
 * 그래서 응답의 LocalDateTime 은 KST 값이고, 오프셋이 없으면 브라우저가 시점을 알 수 없다(GitHub `…Z` 와 섞어 정렬하면 9시간 틀어짐).
 * 각 응답의 `…At` 필드를 모두 모아 오프셋이 있는지, 실제 시점(방금)과 맞는지 확인한다.
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
class ApiDateTimeOffsetIntegrationTest {

    companion object {
        private val originalZone: TimeZone = TimeZone.getDefault()

        init {
            // stg 와 같게 — 컨텍스트·커넥션 풀이 뜨기 전에
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @AfterAll
        @JvmStatic
        fun restoreZone() = TimeZone.setDefault(originalZone)
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val om = ObjectMapper()

    @MockitoBean private lateinit var authorSummaryReader: AuthorSummaryReaderPort
    @MockitoBean private lateinit var workspaceMemberReader: WorkspaceMemberReaderPort
    @MockitoBean private lateinit var followedUserReader: FollowedUserReaderPort
    @MockitoBean private lateinit var workspaceQuery: WorkspaceQueryPort

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    private fun call(req: MockHttpServletRequestBuilder, uid: Long, body: Any? = null): JsonNode {
        val r = req.with(asUser(uid)).apply {
            if (body != null) contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body))
        }
        val res = mvc.perform(r).andReturn().response
        assertTrue(res.status in 200..299, "${res.status} ${res.contentAsString}")
        return om.readTree(res.contentAsString.ifBlank { "{}" })
    }

    /** 응답 안의 모든 `…At` 문자열 필드 (경로 → 값). */
    private fun timeFields(node: JsonNode, path: String = "$"): List<Pair<String, String>> = when {
        node.isObject -> node.properties().flatMap { (k, v) ->
            if (k.endsWith("At") && v.isTextual) listOf("$path.$k" to v.asText()) else timeFields(v, "$path.$k")
        }
        node.isArray -> node.flatMapIndexed { i, v -> timeFields(v, "$path[$i]") }
        else -> emptyList()
    }

    /** 모든 `…At` 가 오프셋을 갖고, [skip] 이 아닌 것은 [since] 이후 ~ 지금 사이의 시점이어야 한다. */
    private fun assertOffsetAndNow(name: String, node: JsonNode, since: Instant, skip: Set<String> = emptySet()) {
        val fields = timeFields(node)
        assertTrue(fields.isNotEmpty(), "$name: 시각 필드 없음 — $node")
        val now = Instant.now()
        fields.forEach { (path, raw) ->
            val at = runCatching { OffsetDateTime.parse(raw).toInstant() }
                .getOrElse { fail("$name $path = \"$raw\" — 오프셋 없음") }
            if (skip.none { path.endsWith(".$it") }) {
                assertTrue(
                    !at.isBefore(since.minusSeconds(1)) && !at.isAfter(now.plusSeconds(1)),
                    "$name $path = \"$raw\" → $at, 실제 시점 [$since, $now] 밖",
                )
            }
        }
    }

    @Test
    fun `공개 API 의 시각 응답은 오프셋을 포함하고 실제 시점과 같다`() {
        val me = 41L
        val other = 42L
        given(followedUserReader.findFollowingIds(eq(me), anyInt())).willReturn(listOf(other))
        val since = Instant.now()

        // 브라우저 datetime-local 이 보내는 KST 벽시계 — 응답은 같은 순간을 +09:00 으로
        val created = call(
            post("/api/v1/error-cases"), me,
            mapOf("title" to "시간대 확인 케이스", "visibility" to "PUBLIC", "occurredAt" to "2026-10-30T19:00:00"),
        )
        assertOffsetAndNow("POST case", created, since, skip = setOf("occurredAt"))
        val caseId = created["id"].asLong()

        val step = call(post("/api/v1/error-cases/$caseId/steps"), me, mapOf("title" to "재현", "status" to "IN_PROGRESS"))
        assertOffsetAndNow("POST step", step, since)
        val stepId = step["step"]["id"].asLong()
        call(post("/api/v1/error-cases/$caseId/solutions"), me, mapOf("title" to "해결", "stepIds" to listOf(stepId)))
        call(post("/api/v1/error-cases/$caseId/comments"), other, mapOf("body" to "확인했어요"))
        call(post("/api/v1/error-cases/$caseId/watchlist"), me)
        call(post("/api/v1/error-cases"), other, mapOf("title" to "팔로우 대상 케이스", "visibility" to "PUBLIC"))

        // DB 는 UTC 벽시계(stg 와 같음) — 응답의 +09:00 은 이 값을 KST 로 되돌린 같은 순간이어야 한다
        val dbStep = jdbc.queryForObject("SELECT created_at::text FROM error_case_step WHERE id = ?", String::class.java, stepId)!!
        val apiStep = call(get("/api/v1/error-cases/$caseId/steps"), me)
        val apiStepAt = OffsetDateTime.parse(timeFields(apiStep).first { it.first.endsWith(".createdAt") }.second)
        assertEquals(Instant.parse(dbStep.replace(' ', 'T') + "Z"), apiStepAt.toInstant(), "DB(UTC) $dbStep ↔ API $apiStepAt")
        assertEquals("+09:00", apiStepAt.offset.id, "KST 벽시계에 +09:00 — $apiStepAt")
        println("[tz] DB(UTC)=$dbStep  API=${timeFields(apiStep).first { it.first.endsWith(".createdAt") }.second}")

        val detail = call(get("/api/v1/error-cases/$caseId"), me)
        assertOffsetAndNow("GET case", detail, since, skip = setOf("occurredAt"))
        assertEquals(
            OffsetDateTime.parse("2026-10-30T19:00:00+09:00").toInstant(),
            OffsetDateTime.parse(detail["occurredAt"].asText()).toInstant(),
            "occurredAt — 입력한 KST 벽시계와 같은 순간",
        )

        assertOffsetAndNow("GET cases", call(get("/api/v1/error-cases"), me), since, skip = setOf("occurredAt"))
        assertOffsetAndNow("GET steps", apiStep, since)
        assertOffsetAndNow("GET solutions", call(get("/api/v1/error-cases/$caseId/solutions"), me), since)
        assertOffsetAndNow("GET comments", call(get("/api/v1/error-cases/$caseId/comments"), me), since)
        assertOffsetAndNow("GET watchlist", call(get("/api/v1/users/me/watchlist"), me), since)
        assertOffsetAndNow("GET watchlist-feed", call(get("/api/v1/users/me/watchlist-feed"), me), since)
        assertOffsetAndNow("GET following-feed", call(get("/api/v1/users/me/following-feed"), me), since)
        assertOffsetAndNow("GET recent-active-cases", call(get("/api/v1/users/me/recent-active-cases"), me), since)
        assertOffsetAndNow("GET recent-activities", call(get("/api/v1/users/me/recent-activities"), me), since)
        assertOffsetAndNow("GET user recent-activities", call(get("/api/v1/users/$me/recent-activities"), other), since)
    }
}
