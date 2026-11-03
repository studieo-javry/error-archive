package org.studieojavry.coreapi.errorcase.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubEvidenceReconciler
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent.Type
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.infrastructure.api.GithubRateLimitTracker
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseGithubEventJpaRepository
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 놓친 웹훅 재동기화 — 웹훅이 오지 않은 채 GitHub 상태가 바뀐 상황을 목(GitHub API)으로 만들고 [GithubEvidenceReconciler] 를 직접 돌린다.
 * 주기 실행은 test 프로필에서 꺼져 있다(core.github.reconcile.enabled=false).
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
class GithubReconcileIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val seq = AtomicLong(41_000)
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var installations: GithubInstallationRepositoryPort
    @Autowired private lateinit var eventRepo: CaseGithubEventJpaRepository
    @Autowired private lateinit var reconciler: GithubEvidenceReconciler
    @Autowired private lateinit var rateLimits: GithubRateLimitTracker
    @MockitoBean private lateinit var githubApi: GithubAppApiPort
    @MockitoBean private lateinit var workspaceQuery: WorkspaceQueryPort

    private lateinit var mvc: MockMvc
    private val om = ObjectMapper()
    private val t0 = Instant.now().minus(Duration.ofDays(1))

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
        Mockito.reset(githubApi)
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

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

    private fun prItem(
        s: Seeded, number: Int, state: String = "OPEN", head: String = "head-$number", merge: String? = null,
        updatedAt: Instant = t0, closing: List<Int> = emptyList(),
    ) = GithubAppApiPort.PullRequestItem(
        s.repoId,
        GithubPullRequestSnapshot(
            s.repoId, number, "fix: 주문 발행 재시도", "https://github.com/${s.fullName}/pull/$number", state, false, "jin",
            head, "main", 12, 3, 2, t0, if (state == "MERGED") updatedAt else null, if (state != "OPEN") updatedAt else null,
            updatedAt, mergeCommitSha = merge,
        ),
        body = null, defaultBranch = "main", closingIssues = closing.map { GithubItemRef(s.repoId, it) },
    )

    private fun issueItem(s: Seeded, number: Int, state: String = "OPEN", updatedAt: Instant = t0, candidates: List<Int> = emptyList()) =
        GithubAppApiPort.IssueItem(
            s.repoId,
            GithubIssueSnapshot(
                s.repoId, number, "주문 누락", "https://github.com/${s.fullName}/issues/$number", state, "reporter", t0,
                if (state == "CLOSED") updatedAt else null, updatedAt,
            ),
            body = "본문", labels = emptyList(),
            pullRequestCandidates = candidates.map { GithubAppApiPort.PullRequestCandidate(s.repoId, s.fullName, it) },
        )

    private fun stubItem(s: Seeded, number: Int, item: GithubAppApiPort.GithubItem?) {
        Mockito.`when`(githubApi.fetchItem(s.installationId, s.owner, s.name, number)).thenReturn(item)
    }

    private fun stubRuns(s: Seeded, sha: String, vararg runs: Pair<String, String?>) {
        Mockito.`when`(githubApi.fetchCommitCheckRuns(s.installationId, s.owner, s.name, sha)).thenReturn(
            runs.map { (n, c) -> CiSummary.Run(n, if (c == null) "in_progress" else "completed", c, if (c == null) null else t0.plusSeconds(60)) },
        )
    }

    private fun link(uid: Long, caseId: Long, url: String) = mvc.perform(
        post("/api/v1/error-cases/$caseId/github/links").with(asUser(uid))
            .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mapOf("url" to url))),
    )

    private fun evidence(uid: Long, caseId: Long) = mvc.perform(get("/api/v1/error-cases/$caseId/github").with(asUser(uid)))
    private fun eventTypes(caseId: Long) = eventRepo.findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId).map { Type.valueOf(it.type) }
    private fun fetchCount(s: Seeded, number: Int) =
        Mockito.mockingDetails(githubApi).invocations.count { it.method.name == "fetchItem" && it.arguments.toList() == listOf(s.installationId, s.owner, s.name, number) }

    @Test
    fun `웹훅을 놓친 사이 PR 이 머지되고 CI 가 끝남 - 재동기화가 상태·CI·타임라인을 맞추고, 1시간 안엔 다시 조회하지 않음`() {
        val me = 501L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 10, prItem(s, 10, head = "c10"))
        stubRuns(s, "c10", "build" to null)
        link(me, caseId, "https://github.com/${s.fullName}/pull/10").andExpect(status().isCreated)
        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].ci.status").value("PENDING"))

        // pull_request closed · check_suite completed 웹훅이 오지 않음
        stubItem(s, 10, prItem(s, 10, state = "MERGED", head = "c10", merge = "m10", updatedAt = t0.plusSeconds(3600)))
        stubRuns(s, "c10", "build" to "success")
        reconciler.reconcileDue(limit = 500)

        evidence(me, caseId)
            .andExpect(jsonPath("$.ungroupedPullRequests[0].state").value("MERGED"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.status").value("SUCCESS"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.source").value("HEAD"))
        val types = eventTypes(caseId)
        assertEquals(1, types.count { it == Type.PR_MERGED })
        assertEquals(1, types.count { it == Type.CI_COMPLETED })

        val calls = fetchCount(s, 10)
        reconciler.reconcileDue(limit = 500)
        assertEquals(calls, fetchCount(s, 10), "방금 확인한 대상은 min-interval(1h) 안에 다시 부르지 않음")
        assertEquals(types, eventTypes(caseId))
    }

    @Test
    fun `Issue 가 닫혔다가 다시 열림 - 웹훅 없이도 ISSUE_CLOSED·ISSUE_REOPENED 를 한 번씩, 상태 그대로면 아무것도 안 남김`() {
        val me = 502L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 20, issueItem(s, 20))
        link(me, caseId, "https://github.com/${s.fullName}/issues/20").andExpect(status().isCreated)

        stubItem(s, 20, issueItem(s, 20, state = "CLOSED", updatedAt = t0.plusSeconds(3600)))
        reconciler.reconcileDue(limit = 500)
        evidence(me, caseId).andExpect(jsonPath("$.issues[0].state").value("CLOSED"))
        assertEquals(1, eventTypes(caseId).count { it == Type.ISSUE_CLOSED })

        stubItem(s, 20, issueItem(s, 20, state = "OPEN", updatedAt = t0.plusSeconds(7200)))
        reconciler.reconcileDue(limit = 500, now = Instant.now().plus(Duration.ofHours(2)))
        evidence(me, caseId).andExpect(jsonPath("$.issues[0].state").value("OPEN"))
        assertEquals(1, eventTypes(caseId).count { it == Type.ISSUE_REOPENED })

        val before = eventTypes(caseId)
        reconciler.reconcileDue(limit = 500, now = Instant.now().plus(Duration.ofHours(4)))
        assertEquals(before, eventTypes(caseId), "바뀐 게 없으면 줄이 늘지 않음(멱등)")
    }

    @Test
    fun `놓친 PR opened - 열린 Issue 재동기화가 소급 부착을 다시 돌려 그 Issue 를 닫는 PR 을 붙임`() {
        val me = 503L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 30, issueItem(s, 30))
        link(me, caseId, "https://github.com/${s.fullName}/issues/30").andExpect(status().isCreated)

        // 서버가 내려가 있던 동안 "Fixes #30" PR 이 열림 — pull_request 웹훅 유실
        stubItem(s, 30, issueItem(s, 30, candidates = listOf(31)))
        stubItem(s, 31, prItem(s, 31, head = "c31", closing = listOf(30)))
        stubRuns(s, "c31", "build" to "success")
        reconciler.reconcileDue(limit = 500)

        evidence(me, caseId)
            .andExpect(jsonPath("$.issues[0].number").value(30))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].number").value(31))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].linkSource").value("VIA_ISSUE"))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].ci.status").value("SUCCESS"))
    }

    @Test
    fun `대상 고르기 - 일주일 넘게 안 바뀐 닫힌 PR 과 뗀 연결은 다시 조회하지 않음`() {
        val me = 504L
        val s = seedRepo(me)
        val caseId = createCase(me)
        val old = Instant.now().minus(Duration.ofDays(30))
        stubItem(s, 40, prItem(s, 40, state = "MERGED", head = "c40", updatedAt = old))
        stubRuns(s, "c40", "build" to "success")
        link(me, caseId, "https://github.com/${s.fullName}/pull/40").andExpect(status().isCreated)
        stubItem(s, 41, prItem(s, 41))
        stubRuns(s, "head-41")
        val linkId = om.readTree(
            link(me, caseId, "https://github.com/${s.fullName}/pull/41").andExpect(status().isCreated).andReturn().response.contentAsString,
        )["linkId"].asLong()
        mvc.perform(delete("/api/v1/error-cases/$caseId/github/links/$linkId").with(asUser(me))).andExpect(status().isNoContent)
        Mockito.clearInvocations(githubApi)

        reconciler.reconcileDue(limit = 500)
        assertEquals(0, fetchCount(s, 40), "오래전에 끝난 PR")
        assertEquals(0, fetchCount(s, 41), "뗀 연결")
    }

    @Test
    fun `호출 한도 예비분에 닿은 설치는 이번 회차에서 미루고 확인 시각도 남기지 않음 - 운영 API 로 바로 다시 돌림`() {
        val me = 505L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 50, prItem(s, 50, head = "c50"))
        stubRuns(s, "c50", "build" to null)
        link(me, caseId, "https://github.com/${s.fullName}/pull/50").andExpect(status().isCreated)

        rateLimits.record(s.installationId, HttpHeaders().apply {
            add("x-ratelimit-limit", "5000"); add("x-ratelimit-remaining", "100")
            add("x-ratelimit-reset", Instant.now().plusSeconds(5).epochSecond.toString()); add("x-ratelimit-resource", "core")
        })
        Mockito.clearInvocations(githubApi)
        val held = reconciler.reconcileDue(limit = 500)
        assertTrue(held.deferred >= 1)
        assertEquals(0, fetchCount(s, 50))

        Thread.sleep(6_000)   // 한도 창 리셋
        stubRuns(s, "c50", "build" to "failure")
        val res = om.readTree(
            mvc.perform(post("/internal/admin/github/reconcile").with(asUser(me)).param("limit", "500"))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        )
        assertTrue(res["checked"].asInt() >= 1, res.toString())
        assertEquals(1, fetchCount(s, 50), "미뤘던 대상은 확인 시각이 없어 바로 다시 고름")
        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].ci.status").value("FAILURE"))
    }
}
