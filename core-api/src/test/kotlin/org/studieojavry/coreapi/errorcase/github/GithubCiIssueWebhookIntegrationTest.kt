package org.studieojavry.coreapi.errorcase.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubWebhookInboxPort
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent.Type
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxEntity.InboxStatus
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxProcessor
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseGithubEventJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubIssueJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubItemKey
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GitHub 연동 P1-d 통합 — `check_suite`(머지 후 CI 포함) · `issues` 웹훅, 활동 리비전, 호출 한도 미루기.
 * 웹훅은 inbox 에 직접 적재 → 처리기 1건 구동. GitHub API 는 [GithubAppApiPort] 목.
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
class GithubCiIssueWebhookIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val seq = AtomicLong(31_000)
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var installations: GithubInstallationRepositoryPort
    @Autowired private lateinit var inbox: GithubWebhookInboxPort
    @Autowired private lateinit var processor: GithubWebhookInboxProcessor
    @Autowired private lateinit var inboxRepo: GithubWebhookInboxJpaRepository
    @Autowired private lateinit var eventRepo: CaseGithubEventJpaRepository
    @Autowired private lateinit var issueRepo: GithubIssueJpaRepository
    @MockitoBean private lateinit var githubApi: GithubAppApiPort
    @MockitoBean private lateinit var workspaceQuery: WorkspaceQueryPort

    private lateinit var mvc: MockMvc
    private val om = ObjectMapper()
    private val t0 = Instant.parse("2026-10-20T01:00:00Z")

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
        Mockito.reset(githubApi)
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    // ── seed helpers ──────────────────────────────────────────
    private data class Seeded(val installationId: Long, val repoId: Long, val fullName: String) {
        val owner get() = fullName.substringBefore('/')
        val name get() = fullName.substringAfter('/')
    }

    private fun seedRepo(installedBy: Long): Seeded {
        val id = seq.incrementAndGet()
        installations.save(GithubInstallation.connect(id, "acme-$id", "Organization", null, installedBy))
        val fullName = "acme-$id/payment-api"
        installations.saveRepositories(listOf(GithubRepository.of(id * 10, id, fullName, true)))
        return Seeded(id, id * 10, fullName)
    }

    private fun createCase(uid: Long): Long {
        val res = mvc.perform(
            post("/api/v1/error-cases").with(asUser(uid)).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("title" to "결제 후 주문 누락", "visibility" to "PRIVATE"))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return om.readTree(res)["id"].asLong()
    }

    private fun prItem(s: Seeded, number: Int, state: String = "OPEN", head: String = "head-$number", merge: String? = null, updatedAt: Instant = t0.plusSeconds(3600)) =
        GithubAppApiPort.PullRequestItem(
            s.repoId,
            GithubPullRequestSnapshot(
                s.repoId, number, "fix: 주문 발행 재시도", "https://github.com/${s.fullName}/pull/$number", state, false, "jin",
                head, "alpha", 12, 3, 2, t0, if (state == "MERGED") updatedAt else null, if (state != "OPEN") updatedAt else null,
                updatedAt, mergeCommitSha = merge,
            ),
            body = null, defaultBranch = "main", closingIssues = emptyList(),
        )

    private fun issueItem(s: Seeded, number: Int, state: String = "OPEN", updatedAt: Instant = t0) = GithubAppApiPort.IssueItem(
        s.repoId,
        GithubIssueSnapshot(
            s.repoId, number, "주문 누락", "https://github.com/${s.fullName}/issues/$number", state, "reporter", t0,
            if (state == "CLOSED") updatedAt else null, updatedAt,
        ),
        body = "본문", labels = emptyList(),
    )

    private fun stubItem(s: Seeded, number: Int, item: GithubAppApiPort.GithubItem?) {
        Mockito.`when`(githubApi.fetchItem(s.installationId, s.owner, s.name, number)).thenReturn(item)
    }

    private fun stubRuns(s: Seeded, sha: String, vararg runs: Pair<String, String?>) {
        Mockito.`when`(githubApi.fetchCommitCheckRuns(s.installationId, s.owner, s.name, sha)).thenReturn(
            runs.map { (n, c) -> CiSummary.Run(n, if (c == null) "in_progress" else "completed", c, if (c == null) null else t0.plusSeconds(7200)) },
        )
    }

    private fun link(uid: Long, caseId: Long, url: String) = mvc.perform(
        post("/api/v1/error-cases/$caseId/github/links").with(asUser(uid))
            .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mapOf("url" to url))),
    )

    private fun evidence(uid: Long, caseId: Long) = mvc.perform(get("/api/v1/error-cases/$caseId/github").with(asUser(uid)))
    private fun eventsJson(uid: Long, caseId: Long): JsonNode = om.readTree(
        mvc.perform(get("/api/v1/error-cases/$caseId/github/events").with(asUser(uid)).param("size", "100"))
            .andExpect(status().isOk).andReturn().response.contentAsString,
    )

    private fun ciRows(caseId: Long) = eventRepo.findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId).filter { it.type == Type.CI_COMPLETED.name }
    private fun eventTypes(caseId: Long) = eventRepo.findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId).map { Type.valueOf(it.type) }

    private fun deliver(event: String, action: String, s: Seeded, body: Map<String, Any?>): String {
        val payload = om.writeValueAsString(
            mapOf("action" to action, "installation" to mapOf("id" to s.installationId), "repository" to mapOf("id" to s.repoId, "full_name" to s.fullName)) + body,
        )
        val delivery = UUID.randomUUID().toString()
        inbox.insertIfAbsent(GithubWebhookInboxPort.InboundDelivery(delivery, event, action, s.installationId, payload))
        processor.processOne(delivery)
        return delivery
    }

    private fun status(delivery: String) = inboxRepo.findById(delivery).orElseThrow().status

    /** check_suite completed — [prNumbers] 는 payload `pull_requests`(push 로 돈 CI 면 비어 있음). */
    private fun checkSuite(s: Seeded, sha: String, prNumbers: List<Int> = emptyList()) = deliver(
        "check_suite", "completed", s,
        mapOf(
            "check_suite" to mapOf(
                "head_sha" to sha, "status" to "completed", "conclusion" to "success",
                "pull_requests" to prNumbers.map { mapOf("number" to it, "base" to mapOf("repo" to mapOf("id" to s.repoId))) },
            ),
        ),
    )

    private fun issuesWebhook(s: Seeded, action: String, number: Int, updatedAt: String = "2026-10-21T05:00:00Z") =
        deliver("issues", action, s, mapOf("issue" to mapOf("number" to number, "updated_at" to updatedAt)))

    // ── (a) 연결 시점 머지 후 CI ─────────────────────────────
    @Test
    fun `머지된 PR 연결 - head 에 검사가 없으면 머지 커밋 검사를 보여 줌(source=MERGE), 타임라인도 머지 후 CI 한 줄`() {
        val me = 401L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 100, prItem(s, 100, state = "MERGED", head = "head-100", merge = "merge-100"))
        stubRuns(s, "head-100")
        stubRuns(s, "merge-100", "detect" to "success", "test" to "success", "build-push" to "success", "bump-manifest" to "success")

        link(me, caseId, "https://github.com/${s.fullName}/pull/100").andExpect(status().isCreated)

        evidence(me, caseId)
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.status").value("SUCCESS"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.source").value("MERGE"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.sha").value("merge-100"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.passed").value(4))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.stale").value(false))
        val ci = eventsJson(me, caseId)["events"].first { it["type"].asText() == "CI_COMPLETED" }
        assertEquals("MERGE", ci["data"]["source"].asText())
        assertEquals("merge-100", ci["data"]["sha"].asText())
    }

    @Test
    fun `열린 PR 은 head 에 검사가 없어도 머지 커밋을 찾지 않음 - CI 정보 없음`() {
        val me = 402L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 110, prItem(s, 110))
        stubRuns(s, "head-110")

        link(me, caseId, "https://github.com/${s.fullName}/pull/110").andExpect(status().isCreated)
        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].ci").doesNotExist())
        assertTrue(ciRows(caseId).isEmpty())
    }

    // ── check_suite ─────────────────────────────────────────
    @Test
    fun `연결 후 머지 - pull_requests 가 빈 머지 커밋 check_suite 를 커밋→PR 조회로 이어 붙임, 리비전이 오름`() {
        val me = 403L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 120, prItem(s, 120))
        stubRuns(s, "head-120")
        link(me, caseId, "https://github.com/${s.fullName}/pull/120").andExpect(status().isCreated)
        val revBefore = eventsJson(me, caseId)["revision"].asLong()

        // 머지됨 — 장부는 아직 머지 커밋을 모름(PR 웹훅보다 check_suite 가 먼저 처리된 경우)
        stubItem(s, 120, prItem(s, 120, state = "MERGED", merge = "merge-120", updatedAt = t0.plusSeconds(7200)))
        Mockito.`when`(githubApi.listPullRequestNumbersForCommit(s.installationId, s.owner, s.name, "merge-120")).thenReturn(listOf(120))
        stubRuns(s, "merge-120", "test" to "success", "build-push" to "failure")

        assertEquals(InboxStatus.DONE, status(checkSuite(s, "merge-120")))

        evidence(me, caseId)
            .andExpect(jsonPath("$.ungroupedPullRequests[0].state").value("MERGED"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.status").value("FAILURE"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.source").value("MERGE"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.failed[0]").value("build-push"))
        val page = eventsJson(me, caseId)
        val ci = page["events"].single { it["type"].asText() == "CI_COMPLETED" }
        assertEquals("FAILURE", ci["data"]["conclusion"].asText())
        assertEquals("MERGE", ci["data"]["source"].asText())
        assertTrue(page["revision"].asLong() > revBefore, "활동 줄이 생겼으니 리비전이 오름")
    }

    @Test
    fun `PR head 커밋 CI - 진행 중은 카드에만, 타임라인엔 최종 결과만 한 줄 · 같은 결과가 다시 와도 리비전 그대로`() {
        val me = 404L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 130, prItem(s, 130, head = "c130"))
        stubRuns(s, "c130")
        link(me, caseId, "https://github.com/${s.fullName}/pull/130").andExpect(status().isCreated)

        stubRuns(s, "c130", "build" to "success", "it" to null)
        assertEquals(InboxStatus.DONE, status(checkSuite(s, "c130", listOf(130))))
        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].ci.status").value("PENDING"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.source").value("HEAD"))
        assertTrue(ciRows(caseId).isEmpty(), "진행 중은 타임라인에 남기지 않음")

        stubRuns(s, "c130", "build" to "success", "it" to "success")
        assertEquals(InboxStatus.DONE, status(checkSuite(s, "c130", listOf(130))))
        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].ci.status").value("SUCCESS"))
        assertEquals(1, ciRows(caseId).size)
        val rev = eventsJson(me, caseId)["revision"].asLong()

        // 다른 CI 앱의 suite 가 같은 커밋으로 또 옴 — 집계 결과 그대로면 줄도 리비전도 그대로
        assertEquals(InboxStatus.DONE, status(checkSuite(s, "c130", listOf(130))))
        assertEquals(1, ciRows(caseId).size)
        assertEquals(rev, eventsJson(me, caseId)["revision"].asLong())
    }

    @Test
    fun `head CI 가 있으면 머지 커밋 CI 는 카드 요약을 덮지 않고 타임라인에만 머지 후 CI 로 남음`() {
        val me = 405L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 140, prItem(s, 140, state = "MERGED", head = "h140", merge = "m140"))
        stubRuns(s, "h140", "test" to "success")
        link(me, caseId, "https://github.com/${s.fullName}/pull/140").andExpect(status().isCreated)

        stubRuns(s, "m140", "test" to "success", "deploy" to "failure")
        assertEquals(InboxStatus.DONE, status(checkSuite(s, "m140")))

        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].ci.source").value("HEAD"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.status").value("SUCCESS"))
        val sources = ciRows(caseId).map { om.readTree(it.data)["source"].asText() }.sorted()
        assertEquals(listOf("HEAD", "MERGE"), sources)
    }

    @Test
    fun `사전 필터 - 붙은 PR 이 없는 레포의 check_suite 는 GitHub 를 부르지 않고 SKIPPED, completed 외 action 도 SKIPPED`() {
        val s = seedRepo(406L)
        assertEquals(InboxStatus.SKIPPED, status(checkSuite(s, "zzz")))
        assertEquals(InboxStatus.SKIPPED, status(deliver("check_suite", "requested", s, mapOf("check_suite" to mapOf("head_sha" to "zzz")))))
        Mockito.verify(githubApi, Mockito.never()).listPullRequestNumbersForCommit(anyLong(), anyString(), anyString(), anyString())
        Mockito.verify(githubApi, Mockito.never()).fetchCommitCheckRuns(anyLong(), anyString(), anyString(), anyString())
    }

    @Test
    fun `호출 한도 초과 - 시도 횟수를 쓰지 않고 한도가 풀릴 때까지 미룸`() {
        val me = 407L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 150, prItem(s, 150, head = "c150"))
        stubRuns(s, "c150")
        link(me, caseId, "https://github.com/${s.fullName}/pull/150").andExpect(status().isCreated)

        Mockito.`when`(githubApi.fetchCommitCheckRuns(s.installationId, s.owner, s.name, "c150"))
            .thenThrow(GithubApiException("rate limited", retryAfter = Duration.ofMinutes(10)))
        val before = Instant.now()
        val delivery = checkSuite(s, "c150", listOf(150))

        val row = inboxRepo.findById(delivery).orElseThrow()
        assertEquals(InboxStatus.PENDING, row.status)
        assertEquals(0, row.attempts, "한도 초과는 실패 횟수에 넣지 않음")
        assertTrue(row.nextRetryAt.isAfter(before.plus(Duration.ofMinutes(9))), "${row.nextRetryAt}")
    }

    @Test
    fun `사용자 API - GitHub 호출 한도 초과는 429 + Retry-After(올림 초), 그 밖의 GitHub 장애는 503`() {
        val me = 410L
        val s = seedRepo(me)
        val caseId = createCase(me)
        Mockito.`when`(githubApi.fetchItem(s.installationId, s.owner, s.name, 160))
            .thenThrow(GithubApiException("rate limited", retryAfter = Duration.ofMillis(89_500)))
        Mockito.`when`(githubApi.fetchItem(s.installationId, s.owner, s.name, 161))
            .thenThrow(GithubApiException("GitHub API fetchItem 실패: 502"))

        link(me, caseId, "https://github.com/${s.fullName}/pull/160")
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "90"))
            .andExpect(jsonPath("$.code").value("GITHUB_RATE_LIMITED"))
            .andExpect(jsonPath("$.retryable").value(true))
        link(me, caseId, "https://github.com/${s.fullName}/pull/161").andExpect(status().isServiceUnavailable)
    }

    // ── issues ──────────────────────────────────────────────
    @Test
    fun `issues 웹훅 - 붙은 Issue 가 닫히면 ISSUE_CLOSED, 다시 열리면 ISSUE_REOPENED, 지워지면 삭제 표시`() {
        val me = 408L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 42, issueItem(s, 42))
        link(me, caseId, "https://github.com/${s.fullName}/issues/42").andExpect(status().isCreated)

        stubItem(s, 42, issueItem(s, 42, state = "CLOSED", updatedAt = t0.plusSeconds(3600)))
        assertEquals(InboxStatus.DONE, status(issuesWebhook(s, "closed", 42)))
        evidence(me, caseId).andExpect(jsonPath("$.issues[0].state").value("CLOSED"))

        stubItem(s, 42, issueItem(s, 42, state = "OPEN", updatedAt = t0.plusSeconds(7200)))
        assertEquals(InboxStatus.DONE, status(issuesWebhook(s, "reopened", 42, "2026-10-20T03:00:00Z")))
        evidence(me, caseId).andExpect(jsonPath("$.issues[0].state").value("OPEN"))

        val types = eventTypes(caseId)
        assertEquals(1, types.count { it == Type.ISSUE_CLOSED })
        assertEquals(1, types.count { it == Type.ISSUE_REOPENED })

        assertEquals(InboxStatus.DONE, status(issuesWebhook(s, "deleted", 42)))
        assertTrue(issueRepo.findById(GithubItemKey(s.repoId, 42)).orElseThrow().deleted)
        evidence(me, caseId).andExpect(jsonPath("$.issues[0].number").value(42))
    }

    @Test
    fun `issues 웹훅 - 어느 Case 에도 안 붙은 Issue 는 GitHub 를 부르지 않고 SKIPPED`() {
        val s = seedRepo(409L)
        assertEquals(InboxStatus.SKIPPED, status(issuesWebhook(s, "closed", 77)))
        assertEquals(InboxStatus.SKIPPED, status(issuesWebhook(s, "labeled", 77)))
        Mockito.verify(githubApi, Mockito.never()).fetchItem(anyLong(), anyString(), anyString(), anyInt())
    }
}
