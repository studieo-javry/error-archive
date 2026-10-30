package org.studieojavry.coreapi.errorcase.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort.WorkspaceRole
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GitHub 활동(근거 타임라인) 커서 조회의 경계·한계·권한·실행 계획.
 *
 * 보장하는 것 — **데이터가 바뀌지 않는 동안** `(occurredAt↓, id↓)` 키셋으로 나눠 받으면 겹침·빠짐 없음, 같은 시각은 id 로 결정적.
 * 보장하지 않는 것(현 API 의 한계, 아래 테스트가 그대로 기록) —
 *  - 이미 지나간 구간에 **늦게 저장된 과거 사건**(예: Issue 연결 때 3일 전 PR 이 소급 저장)은 이어 받는 커서로는 안 나온다.
 *  - CI 집계 upsert 가 `occurred_at` 을 옮기면 같은 id 가 다시 나오거나(FE 는 id 로 합침), 아직 안 받은 구간에서 빠질 수 있다.
 *  이런 경우의 단서는 `totalCount` 와 받은 고유 id 수의 차이 — FE 는 이걸로 "처음부터 다시 받기" 를 판단한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "core.github.enabled=true",
    "core.github.app-id=1",
    "core.github.app-slug=error-archive-test",
    "core.github.client-id=cid",
    "core.github.client-secret=csecret",
    "core.github.private-key-pem=unused-by-mock",
    "core.github.webhook-secret=whsec",
    "core.github.state-secret=state-secret",
    "core.github.inbox.poll-delay-ms=3600000",
])
class GithubActivityCursorIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val seq = AtomicLong(19_000)
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var installations: GithubInstallationRepositoryPort
    @Autowired private lateinit var evidencePort: GithubEvidenceRepositoryPort
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoBean private lateinit var githubApi: GithubAppApiPort
    @MockitoBean private lateinit var workspaceQuery: WorkspaceQueryPort

    private lateinit var mvc: MockMvc
    private val om = ObjectMapper()
    private val t0 = Instant.parse("2026-10-20T01:00:00Z")

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    private data class Seeded(val repoId: Long, val fullName: String)

    private fun seedRepo(installedBy: Long, workspaceId: Long?, isPrivate: Boolean = true): Seeded {
        val id = seq.incrementAndGet()
        installations.save(GithubInstallation.connect(id, "acme-$id", "Organization", workspaceId, installedBy))
        val fullName = "acme-$id/payment-api"
        installations.saveRepositories(listOf(GithubRepository.of(id * 10, id, fullName, isPrivate)))
        return Seeded(id * 10, fullName)
    }

    private fun createCase(uid: Long, visibility: String, workspaceId: Long? = null): Long {
        val body = mapOf("title" to "HikariCP connection timeout", "visibility" to visibility, "workspaceId" to workspaceId)
        val res = mvc.perform(
            post("/api/v1/error-cases").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return om.readTree(res)["id"].asLong()
    }

    private fun event(caseId: Long, s: Seeded, number: Int, at: Instant, key: String = "T:$caseId:$number") =
        CaseGithubEvent(caseId, CaseGithubEvent.Type.PR_OPENED, GithubRefKind.PULL_REQUEST, s.repoId, number, at, emptyMap(), key)

    private fun ci(caseId: Long, s: Seeded, number: Int, at: Instant, sha: String) =
        CaseGithubEvent(
            caseId, CaseGithubEvent.Type.CI_COMPLETED, GithubRefKind.PULL_REQUEST, s.repoId, number, at,
            mapOf("sha" to sha, "conclusion" to "FAILURE", "total" to 2, "passed" to 1, "failed" to listOf("integration-test")),
            "CI:${s.repoId}:$sha", replaceable = true,
        )

    /** 실제 기록처럼 쓰기 트랜잭션 안에서. */
    private fun record(vararg events: CaseGithubEvent) =
        TransactionTemplate(txManager).executeWithoutResult { events.forEach(evidencePort::recordEvent) }

    /** [n] 건 — 세 건씩 같은 시각(동순위)이 섞이게. */
    private fun seedEvents(caseId: Long, s: Seeded, n: Int, base: Int = 1000) =
        record(*Array(n) { i -> event(caseId, s, base + i, t0.plusSeconds((i / 3) * 60L)) })

    private fun page(uid: Long, caseId: Long, cursor: String? = null, size: Int? = null, expect: Int = 200): JsonNode {
        val res = mvc.perform(
            get("/api/v1/error-cases/$caseId/github/events").with(asUser(uid))
                .apply { cursor?.let { param("cursor", it) }; size?.let { param("size", it.toString()) } },
        ).andReturn().response
        assertEquals(expect, res.status, res.contentAsString)
        return om.readTree(res.contentAsString.ifBlank { "{}" })
    }

    private fun JsonNode.ids() = this["events"].map { it["id"].asLong() }
    private fun JsonNode.cursor(): String? = this["nextCursor"].takeUnless { it.isNull }?.asText()

    /** DB 기준 기대 순서 — occurred_at↓, id↓. */
    private fun expectedOrder(caseId: Long): List<Long> =
        jdbc.queryForList("SELECT id FROM case_github_event WHERE case_id = ? ORDER BY occurred_at DESC, id DESC", Long::class.java, caseId).filterNotNull()

    // ── 경계 ─────────────────────────────────────────────────
    @Test
    fun `경계 건수(0·1·8·20·21·100·101) × 쪽 크기(8·20·100) - 끝까지 넘기면 DB 순서와 같고 겹침·빠짐 없음`() {
        val me = 301L
        val s = seedRepo(me, null)
        for (n in listOf(0, 1, 8, 20, 21, 100, 101)) {
            val caseId = createCase(me, "PRIVATE")
            seedEvents(caseId, s, n)
            val expected = expectedOrder(caseId)
            assertEquals(n, expected.size)

            // 근거 조회의 첫 쪽(20건)
            val first = om.readTree(
                mvc.perform(get("/api/v1/error-cases/$caseId/github").with(asUser(me))).andExpect(status().isOk)
                    .andReturn().response.contentAsString,
            )["activity"]
            assertEquals(expected.take(20), first.ids(), "n=$n 근거 조회 첫 쪽")
            assertEquals(n > 20, first["hasNext"].asBoolean(), "n=$n 첫 쪽 hasNext")
            assertEquals(n, first["totalCount"].asInt())

            for (size in listOf(8, 20, 100)) {
                val seen = mutableListOf<Long>()
                val sizes = mutableListOf<Int>()
                var cursor: String? = null
                do {
                    val p = page(me, caseId, cursor, size)
                    assertEquals(n, p["totalCount"].asInt(), "n=$n size=$size totalCount")
                    sizes += p["events"].size()
                    seen += p.ids()
                    cursor = p.cursor()
                    assertEquals(cursor != null, p["hasNext"].asBoolean(), "n=$n size=$size hasNext ↔ nextCursor")
                } while (cursor != null)
                assertEquals(expected, seen, "n=$n size=$size 순서·겹침·빠짐")
                val expectedSizes = if (n == 0) listOf(0) else expected.chunked(size).map { it.size }
                assertEquals(expectedSizes, sizes, "n=$n size=$size 쪽 크기")
            }
        }
    }

    @Test
    fun `size 는 1~100 으로 맞춤 - 0·음수는 1, 101 이상은 100`() {
        val me = 302L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        seedEvents(caseId, s, 101)
        assertEquals(1, page(me, caseId, size = 0)["events"].size())
        assertEquals(1, page(me, caseId, size = -5)["events"].size())
        assertEquals(100, page(me, caseId, size = 101)["events"].size())
        assertEquals(20, page(me, caseId)["events"].size(), "기본 20")
        page(me, caseId, cursor = "bm90LWEtY3Vyc29y", expect = 400) // base64("not-a-cursor")
    }

    @Test
    fun `같은 시각 30건 - size 8 로 넘겨도 id 로 결정적, 겹침·빠짐 없음`() {
        val me = 303L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        record(*Array(30) { i -> event(caseId, s, 2000 + i, t0) })
        val seen = mutableListOf<Long>()
        var cursor: String? = null
        do {
            val p = page(me, caseId, cursor, 8)
            seen += p.ids()
            cursor = p.cursor()
        } while (cursor != null)
        assertEquals(expectedOrder(caseId), seen)
        assertEquals(seen.sortedDescending(), seen, "같은 시각은 id↓")
    }

    // ── 조회 중 데이터 변화 ───────────────────────────────────
    @Test
    fun `넘기는 중 최신 이벤트가 추가돼도 이어 받는 쪽은 기존 행만 - 겹침·빠짐 없음, 새 행은 첫 쪽 재조회에서`() {
        val me = 304L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        seedEvents(caseId, s, 30)
        val before = expectedOrder(caseId)

        val p1 = page(me, caseId, size = 8)
        record(*Array(5) { i -> event(caseId, s, 3000 + i, t0.plusSeconds(86_400L + i)) }) // 모두 최신

        val seen = p1.ids().toMutableList()
        var cursor = p1.cursor()
        while (cursor != null) {
            val p = page(me, caseId, cursor, 8)
            assertEquals(35, p["totalCount"].asInt(), "건수는 지금 기준")
            seen += p.ids()
            cursor = p.cursor()
        }
        assertEquals(before, seen, "기존 30건 그대로, 새 5건은 이어 받는 쪽에 안 섞임")

        val fresh = page(me, caseId, size = 8)
        assertEquals(expectedOrder(caseId).take(8), fresh.ids())
        assertEquals(5, fresh.ids().count { it !in before }, "새 5건은 첫 쪽에")
    }

    @Test
    fun `한계 - 이미 지나간 구간에 늦게 저장된 과거 사건은 이어 받기로 안 나옴, totalCount 와 받은 수 차이로 알 수 있음`() {
        val me = 305L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        record(*Array(30) { i -> event(caseId, s, 4000 + i, t0.plusSeconds(i * 600L)) }) // 10분 간격, 동순위 없음

        val p1 = page(me, caseId, size = 8)
        val p2 = page(me, caseId, p1.cursor(), 8)
        val fetched = p1.ids() + p2.ids()
        // 받은 16건 사이(4·5번째 사이)의 시각 — 이미 지나간 구간
        val passed = Instant.parse(p1["events"][4]["occurredAt"].asText()).minusSeconds(60)
        // 아직 안 받은 구간 — 커서보다 오래된 시각
        val ahead = Instant.parse(p2["events"].last()["occurredAt"].asText()).minusSeconds(60)
        record(event(caseId, s, 4900, passed, "LATE:passed"), event(caseId, s, 4901, ahead, "LATE:ahead"))
        val (passedId, aheadId) = listOf("LATE:passed", "LATE:ahead").map {
            jdbc.queryForObject("SELECT id FROM case_github_event WHERE case_id = ? AND dedupe_key = ?", Long::class.java, caseId, it)
        }

        val seen = fetched.toMutableList()
        var cursor = p2.cursor()
        var total = 0
        while (cursor != null) {
            val p = page(me, caseId, cursor, 8)
            total = p["totalCount"].asInt()
            seen += p.ids()
            cursor = p.cursor()
        }
        assertTrue(aheadId in seen, "아직 안 받은 구간에 들어간 건 나옴")
        assertFalse(passedId in seen, "지나간 구간에 들어간 건 이어 받기로는 안 나옴(현 API 한계)")
        assertEquals(32, total)
        assertEquals(31, seen.toSet().size, "끝까지 받았는데 고유 id 31 < totalCount 32 → FE 가 처음부터 다시 받을 단서")

        // 처음부터 다시 받으면 빠짐 없음
        val rescan = mutableListOf<Long>()
        var c: String? = null
        do {
            val p = page(me, caseId, c, 8)
            rescan += p.ids()
            c = p.cursor()
        } while (c != null)
        assertEquals(expectedOrder(caseId), rescan)
        assertTrue(passedId in rescan)
    }

    @Test
    fun `한계 - CI 집계 upsert 가 occurredAt 을 옮기면 같은 id 가 다시 나오거나 아직 안 받은 구간에서 빠짐`() {
        val me = 306L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        record(*Array(20) { i -> event(caseId, s, 5000 + i, t0.plusSeconds(i * 600L)) })
        // 최신 쪽 CI 하나, 오래된 쪽 CI 하나
        record(ci(caseId, s, 5100, t0.plusSeconds(20 * 600L), "sha-new"), ci(caseId, s, 5101, t0.plusSeconds(30L), "sha-old"))
        val ciNew = jdbc.queryForObject("SELECT id FROM case_github_event WHERE case_id = ? AND dedupe_key LIKE '%sha-new'", Long::class.java, caseId)
        val ciOld = jdbc.queryForObject("SELECT id FROM case_github_event WHERE case_id = ? AND dedupe_key LIKE '%sha-old'", Long::class.java, caseId)

        val p1 = page(me, caseId, size = 8)
        assertEquals(ciNew, p1.ids().first(), "최신 CI 는 첫 쪽 맨 앞")

        // 이미 받은 CI 를 과거로(커서보다 오래되게), 아직 안 받은 CI 를 최신으로 — 같은 키 upsert, id 유지
        record(ci(caseId, s, 5100, t0.plusSeconds(5L), "sha-new"), ci(caseId, s, 5101, t0.plusSeconds(99 * 600L), "sha-old"))
        assertEquals(ciNew, jdbc.queryForObject("SELECT id FROM case_github_event WHERE case_id = ? AND dedupe_key LIKE '%sha-new'", Long::class.java, caseId))

        val rest = mutableListOf<Long>()
        var cursor = p1.cursor()
        while (cursor != null) {
            val p = page(me, caseId, cursor, 8)
            rest += p.ids()
            cursor = p.cursor()
        }
        assertTrue(ciNew in rest, "이미 받은 CI 가 다시 나옴 — FE 는 id 로 합쳐 한 줄로")
        assertFalse(ciOld in rest, "아직 안 받은 CI 가 최신 쪽으로 옮겨가 이어 받기에선 빠짐(현 API 한계)")
        assertEquals(ciOld, page(me, caseId, size = 8).ids().first(), "첫 쪽 재조회에서는 보임")
    }

    // ── 권한·가림 ────────────────────────────────────────────
    @Test
    fun `권한 - 커서 요청도 매번 지금 권한으로, 탈퇴 후 옛 커서는 403, 다른 Case 커서는 위치로만 쓰임`() {
        val owner = 307L
        val reader = 308L
        val outsider = 309L
        val w = 9301L
        Mockito.`when`(workspaceQuery.getViewerRole(owner, w)).thenReturn(WorkspaceRole.ADMIN)
        Mockito.`when`(workspaceQuery.getViewerRole(reader, w)).thenReturn(WorkspaceRole.READ)
        val s = seedRepo(owner, w)
        val caseId = createCase(owner, "WORKSPACE", w)
        seedEvents(caseId, s, 20)

        val p1 = page(reader, caseId, size = 8)
        Mockito.`when`(workspaceQuery.getViewerRole(reader, w)).thenReturn(null)
        page(reader, caseId, p1.cursor(), 8, expect = 403)
        page(outsider, caseId, p1.cursor(), 8, expect = 403)

        // 다른 Case 에서 받은 커서 — 그 Case 의 행이 아니라 이 Case 의 "그 시각 이전" 으로만 해석
        val other = createCase(owner, "WORKSPACE", w)
        seedEvents(other, s, 20, base = 7000)
        val cross = page(owner, other, p1.cursor(), 100)
        assertTrue(cross.ids().all { it in expectedOrder(other) }, "다른 Case 행은 섞이지 않음")
    }

    @Test
    fun `가림 - 외부인은 비공개 레포 이벤트를 종류·시각·CI 결과만, totalCount 는 가린 행 포함`() {
        val owner = 310L
        val outsider = 311L
        val w = 9302L
        Mockito.`when`(workspaceQuery.getViewerRole(owner, w)).thenReturn(WorkspaceRole.ADMIN)
        val s = seedRepo(owner, w, isPrivate = true)
        val caseId = createCase(owner, "PUBLIC", w)
        seedEvents(caseId, s, 9)
        record(ci(caseId, s, 6000, t0.plusSeconds(99_000L), "sha-mask"))

        val seen = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val p = page(outsider, caseId, cursor, 4)
            assertEquals(10, p["totalCount"].asInt(), "가린 행도 건수에 포함")
            p["events"].forEach { seen.add(it) }
            cursor = p.cursor()
        } while (cursor != null)
        assertEquals(10, seen.size)
        seen.forEach { e ->
            assertTrue(e["masked"].asBoolean())
            listOf("repo", "number", "issue").forEach { k -> assertTrue(e.path(k).isMissingNode || e.path(k).isNull, "$k 없음: $e") }
        }
        val ciRow = seen.first { it["type"].asText() == "CI_COMPLETED" }
        assertEquals(listOf("conclusion"), ciRow["data"].fieldNames().asSequence().toList(), "CI 는 결과만")
        assertNull(seen.firstOrNull { it.toString().contains(s.fullName) || it.toString().contains("integration-test") || it.toString().contains("sha-mask") })
    }

    // ── 실행 계획 ────────────────────────────────────────────
    @Test
    fun `EXPLAIN - 첫 쪽·커서 쪽 모두 ix_case_github_event_cursor 역방향 스캔, 정렬 없이 커서 위치부터 LIMIT 만큼만 읽음`() {
        val me = 312L
        val s = seedRepo(me, null)
        val report = StringBuilder("\n[activity-explain] Testcontainers postgres:16-alpine 기준 — 절대값보다 계획 모양을 본다\n")
        for (n in listOf(100, 1_000, 10_000)) {
            val caseId = createCase(me, "PRIVATE")
            // 다른 Case 행도 섞여 있게(같은 인덱스 안 다른 case_id)
            val noise = createCase(me, "PRIVATE")
            listOf(caseId to n, noise to n).forEach { (c, count) ->
                jdbc.update(
                    """
                    INSERT INTO case_github_event (case_id, type, kind, repo_id, number, occurred_at, data, dedupe_key, created_at)
                    SELECT ?, 'PR_OPENED', 'PULL_REQUEST', ?, g, ?::timestamptz + (g / 3) * interval '1 minute', NULL, 'X:' || g, now()
                      FROM generate_series(1, ?) g
                    """.trimIndent(),
                    c, s.repoId, t0.toString(), count,
                )
            }
            jdbc.execute("ANALYZE case_github_event")
            val mid = jdbc.queryForMap(
                "SELECT occurred_at, id FROM case_github_event WHERE case_id = ? ORDER BY occurred_at DESC, id DESC OFFSET ? LIMIT 1",
                caseId, n / 2,
            )
            val firstSql = "SELECT * FROM case_github_event WHERE case_id = $caseId ORDER BY occurred_at DESC, id DESC LIMIT 21"
            // CaseGithubEventJpaRepository.findOlderThan 과 같은 조건
            val cursorSql = """
                SELECT * FROM case_github_event WHERE case_id = $caseId
                   AND occurred_at <= '${mid["occurred_at"]}' AND (occurred_at < '${mid["occurred_at"]}' OR id < ${mid["id"]})
                 ORDER BY occurred_at DESC, id DESC LIMIT 9
            """.trimIndent()
            val countSql = "SELECT count(*) FROM case_github_event WHERE case_id = $caseId"
            for ((name, sql) in listOf("첫 쪽(LIMIT 21)" to firstSql, "커서 쪽(LIMIT 9)" to cursorSql, "count" to countSql)) {
                val plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) $sql", String::class.java)
                report.append("── n=$n $name\n").append(plan.joinToString("\n") { "   $it" }).append('\n')
                if (name != "count") {
                    val text = plan.joinToString("\n")
                    assertTrue("Index Scan Backward using ix_case_github_event_cursor" in text, "n=$n $name 인덱스 역방향:\n$text")
                    assertFalse(Regex("""(?m)^\s*(->\s*)?Sort\b""").containsMatchIn(text), "n=$n $name 정렬 노드 없음:\n$text")
                    val rows = Regex("""Index Scan Backward.*?actual time=[\d.]+\.\.[\d.]+ rows=(\d+)""").find(text)!!.groupValues[1].toInt()
                    assertTrue(rows <= 21, "n=$n $name 읽은 행 $rows ≤ LIMIT")
                    val removed = Regex("""Rows Removed by Filter: (\d+)""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                    assertTrue(removed <= 3, "n=$n $name 커서 위치부터 읽음 — 걸러 버린 행 $removed:\n$text")
                }
            }
        }
        println(report)
    }
}
