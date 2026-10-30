package org.studieojavry.coreapi.errorcase.github

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubWebhookInboxPort
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent.Type
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxEntity.InboxStatus
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxProcessor
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseGithubEventJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubPrIssueRefJpaRepository
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GitHub 연동 P1-c 통합 — `pull_request` 웹훅으로 닫는 PR 자동 부착·상태 기록.
 * 웹훅은 inbox 에 직접 적재 → 처리기 1건 구동(서명·수신은 P0 테스트가 담당). GitHub API 는 [GithubAppApiPort] 목.
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
class GithubPullRequestWebhookIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val seq = AtomicLong(29_000)
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var installations: GithubInstallationRepositoryPort
    @Autowired private lateinit var inbox: GithubWebhookInboxPort
    @Autowired private lateinit var processor: GithubWebhookInboxProcessor
    @Autowired private lateinit var inboxRepo: GithubWebhookInboxJpaRepository
    @Autowired private lateinit var eventRepo: CaseGithubEventJpaRepository
    @Autowired private lateinit var ledgerRepo: GithubPrIssueRefJpaRepository
    @MockitoBean private lateinit var githubApi: GithubAppApiPort
    @MockitoBean private lateinit var workspaceQuery: WorkspaceQueryPort

    private lateinit var mvc: MockMvc
    private val om = ObjectMapper()
    private val t0 = Instant.parse("2026-10-20T01:00:00Z")

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    // ── seed helpers ──────────────────────────────────────────
    private data class Seeded(val installationId: Long, val repoId: Long, val fullName: String)

    private fun seedRepo(installedBy: Long, workspaceId: Long? = null): Seeded {
        val id = seq.incrementAndGet()
        installations.save(GithubInstallation.connect(id, "acme-$id", "Organization", workspaceId, installedBy))
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

    private fun issueItem(s: Seeded, number: Int) = GithubAppApiPort.IssueItem(
        s.repoId,
        GithubIssueSnapshot(s.repoId, number, "주문 누락", "https://github.com/${s.fullName}/issues/$number", "OPEN", "reporter", t0, null, t0),
        body = "본문", labels = emptyList(),
    )

    private fun prItem(
        s: Seeded,
        number: Int,
        state: String = "OPEN",
        closes: List<GithubItemRef> = emptyList(),
        body: String? = null,
        base: String = "main",
        updatedAt: Instant = t0.plusSeconds(3600),
    ) = GithubAppApiPort.PullRequestItem(
        s.repoId,
        GithubPullRequestSnapshot(
            s.repoId, number, "fix: 주문 발행 재시도", "https://github.com/${s.fullName}/pull/$number", state, false, "jin",
            "sha-$number", base, 12, 3, 2, t0.plusSeconds(1800),
            if (state == "MERGED") updatedAt else null, if (state != "OPEN") updatedAt else null,
            updatedAt,
        ),
        body = body, defaultBranch = "main", closingIssues = closes,
    )

    private fun stubItem(s: Seeded, number: Int, item: GithubAppApiPort.GithubItem?) {
        val (owner, name) = s.fullName.split('/')
        Mockito.`when`(githubApi.fetchItem(s.installationId, owner, name, number)).thenReturn(item)
    }

    private fun link(uid: Long, caseId: Long, url: String) = mvc.perform(
        post("/api/v1/error-cases/$caseId/github/links").with(asUser(uid))
            .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mapOf("url" to url))),
    )

    private fun evidence(uid: Long, caseId: Long) = mvc.perform(get("/api/v1/error-cases/$caseId/github").with(asUser(uid)))
    private fun events(uid: Long, caseId: Long) =
        mvc.perform(get("/api/v1/error-cases/$caseId/github/events").with(asUser(uid)).param("size", "100"))

    /** `pull_request` 웹훅 1건 적재 → 처리 → 최종 상태. payload 는 GitHub 가 보내는 모양 중 우리가 읽는 필드만. */
    private fun prWebhook(s: Seeded, action: String, number: Int, body: String? = null, title: String = "fix: 주문 발행 재시도"): InboxStatus {
        val payload = om.writeValueAsString(
            mapOf(
                "action" to action,
                "installation" to mapOf("id" to s.installationId),
                "repository" to mapOf("id" to s.repoId, "full_name" to s.fullName),
                "pull_request" to mapOf("number" to number, "title" to title, "body" to body, "updated_at" to "2026-10-21T05:00:00Z"),
            ),
        )
        val delivery = UUID.randomUUID().toString()
        inbox.insertIfAbsent(GithubWebhookInboxPort.InboundDelivery(delivery, "pull_request", action, s.installationId, payload))
        processor.processOne(delivery)
        return inboxRepo.findById(delivery).orElseThrow().status
    }

    private fun eventTypes(caseId: Long) = eventRepo.findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId).map { Type.valueOf(it.type) }

    /** Issue #issueNo 를 붙인 Case (소급 후보 없음). */
    private fun caseWithIssue(me: Long, s: Seeded, issueNo: Int): Long {
        val caseId = createCase(me)
        stubItem(s, issueNo, issueItem(s, issueNo))
        link(me, caseId, "https://github.com/${s.fullName}/issues/$issueNo").andExpect(status().isCreated)
        return caseId
    }

    // ── 자동 부착 ─────────────────────────────────────────────
    @Test
    fun `Fixes #N PR 이 열리면 그 Issue 가 붙은 Case 에 자동 부착, 머지되면 머지 기록`() {
        val me = 301L
        val s = seedRepo(me)
        val caseId = caseWithIssue(me, s, 142)
        val issue = GithubItemRef(s.repoId, 142)

        stubItem(s, 500, prItem(s, 500, closes = listOf(issue), body = "Fixes #142"))
        assertEquals(InboxStatus.DONE, prWebhook(s, "opened", 500, body = "Fixes #142"))

        evidence(me, caseId)
            .andExpect(jsonPath("$.issues[0].pullRequests[0].number").value(500))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].linkSource").value("VIA_ISSUE"))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].viaIssue.number").value(142))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].state").value("OPEN"))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].closesIssues[0].number").value(142))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].closesIssues[0].inCase").value(true))
            .andExpect(jsonPath("$.ungroupedPullRequests").isEmpty)
        events(me, caseId).andExpect(jsonPath("$.events[?(@.type=='PR_LINKED')].issue.number").value(org.hamcrest.Matchers.contains(142)))
        assertTrue(eventTypes(caseId).containsAll(listOf(Type.PR_LINKED, Type.PR_OPENED)))

        stubItem(s, 500, prItem(s, 500, state = "MERGED", closes = listOf(issue), body = "Fixes #142", updatedAt = t0.plusSeconds(7200)))
        assertEquals(InboxStatus.DONE, prWebhook(s, "closed", 500, body = "Fixes #142"))

        evidence(me, caseId).andExpect(jsonPath("$.issues[0].pullRequests[0].state").value("MERGED"))
        val types = eventTypes(caseId)
        assertTrue(Type.PR_MERGED in types)
        assertEquals(1, types.count { it == Type.PR_LINKED }, "두 번째 웹훅에서 다시 붙지 않음")
        assertEquals(1, types.count { it == Type.PR_OPENED }, "열림은 한 줄")
    }

    @Test
    fun `이미 붙은 PR - 닫혔다 다시 열리면 닫힘·다시 열림 기록 (Issue 와 무관한 직접 연결 PR 도)`() {
        val me = 302L
        val s = seedRepo(me)
        val caseId = createCase(me)
        stubItem(s, 510, prItem(s, 510))
        Mockito.`when`(githubApi.fetchCommitCheckRuns(anyLong(), anyString(), anyString(), anyString())).thenReturn(emptyList())
        link(me, caseId, "https://github.com/${s.fullName}/pull/510").andExpect(status().isCreated)

        stubItem(s, 510, prItem(s, 510, state = "CLOSED", updatedAt = t0.plusSeconds(7200)))
        assertEquals(InboxStatus.DONE, prWebhook(s, "closed", 510))
        stubItem(s, 510, prItem(s, 510, state = "OPEN", updatedAt = t0.plusSeconds(9000)))
        assertEquals(InboxStatus.DONE, prWebhook(s, "reopened", 510))

        val types = eventTypes(caseId)
        assertTrue(Type.PR_CLOSED in types && Type.PR_REOPENED in types, "$types")
        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].state").value("OPEN"))
    }

    @Test
    fun `사전 필터 - 연결·장부·닫는 키워드 모두 없으면 GitHub 를 부르지 않고 SKIPPED, 관심 없는 action 도 SKIPPED`() {
        val s = seedRepo(303L)
        Mockito.clearInvocations(githubApi)

        assertEquals(InboxStatus.SKIPPED, prWebhook(s, "opened", 520, body = "주문 로직 리팩터링 (#142 관련)"))
        assertEquals(InboxStatus.SKIPPED, prWebhook(s, "labeled", 521, body = "Fixes #142"))
        Mockito.verify(githubApi, Mockito.never()).fetchItem(anyLong(), anyString(), anyString(), anyInt())
    }

    @Test
    fun `기본 브랜치가 아닌 PR - GitHub 가 안 쳐 줘도 닫는 키워드로 부착(KEYWORD), 키워드를 지우면 REF_REMOVED 이고 연결은 유지`() {
        val me = 304L
        val s = seedRepo(me)
        val caseId = caseWithIssue(me, s, 150)

        stubItem(s, 530, prItem(s, 530, closes = emptyList(), body = "Closes #150", base = "alpha"))
        assertEquals(InboxStatus.DONE, prWebhook(s, "opened", 530, body = "Closes #150"))

        evidence(me, caseId).andExpect(jsonPath("$.issues[0].pullRequests[0].number").value(530))
        assertEquals(listOf("KEYWORD"), ledgerRepo.findAllByPrRepoIdAndPrNumber(s.repoId, 530).map { it.source })

        stubItem(s, 530, prItem(s, 530, closes = emptyList(), body = "재시도 정리", base = "alpha", updatedAt = t0.plusSeconds(5000)))
        assertEquals(InboxStatus.DONE, prWebhook(s, "edited", 530, body = "재시도 정리"))

        assertTrue(ledgerRepo.findAllByPrRepoIdAndPrNumber(s.repoId, 530).isEmpty())
        assertTrue(Type.REF_REMOVED in eventTypes(caseId))
        events(me, caseId).andExpect(jsonPath("$.events[?(@.type=='REF_REMOVED')].issue.number").value(org.hamcrest.Matchers.contains(150)))
        // 연결은 유지 — 경유 Issue 도 그대로라 Issue 아래에 남고, 닫는 Issue 목록에서만 빠진다("더 이상 닫지 않음")
        evidence(me, caseId)
            .andExpect(jsonPath("$.issues[0].pullRequests[0].number").value(530))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].viaIssue.number").value(150))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].closesIssues").isEmpty)
    }

    @Test
    fun `기본 브랜치로 가는 PR 은 키워드만으로 붙이지 않음 - GitHub 판정(closingIssuesReferences)을 따름`() {
        val me = 305L
        val s = seedRepo(me)
        val caseId = caseWithIssue(me, s, 160)

        // GitHub 가 "닫는 PR" 로 인정 안 함(예: 코드 블록 안 키워드) → 우리도 안 붙임
        stubItem(s, 540, prItem(s, 540, closes = emptyList(), body = "`fixes #160` 문법 설명"))
        assertEquals(InboxStatus.DONE, prWebhook(s, "opened", 540, body = "`fixes #160` 문법 설명"))

        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests").isEmpty).andExpect(jsonPath("$.issues[0].pullRequests").isEmpty)
    }

    @Test
    fun `관련 없음으로 뗀 PR 은 웹훅이 와도 다시 붙지 않음`() {
        val me = 306L
        val s = seedRepo(me)
        val caseId = caseWithIssue(me, s, 170)
        val issue = GithubItemRef(s.repoId, 170)
        stubItem(s, 550, prItem(s, 550, closes = listOf(issue), body = "Fixes #170"))
        prWebhook(s, "opened", 550, body = "Fixes #170")

        val linkId = om.readTree(evidence(me, caseId).andReturn().response.contentAsString)["issues"][0]["pullRequests"][0]["linkId"].asLong()
        mvc.perform(delete("/api/v1/error-cases/$caseId/github/links/$linkId").with(asUser(me))).andExpect(status().isNoContent)

        assertEquals(InboxStatus.DONE, prWebhook(s, "synchronize", 550, body = "Fixes #170"))
        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests").isEmpty).andExpect(jsonPath("$.issues[0].pullRequests").isEmpty)
    }

    @Test
    fun `범위 불일치 - 남의 개인 연결 저장소 PR 이 내 Issue 를 닫아도 내 Case 에 붙지 않음`() {
        val me = 307L
        val mine = seedRepo(me)
        val others = seedRepo(999L)
        val caseId = caseWithIssue(me, mine, 180)

        stubItem(others, 560, prItem(others, 560, closes = listOf(GithubItemRef(mine.repoId, 180)), body = "Fixes ${mine.fullName}#180"))
        assertEquals(InboxStatus.DONE, prWebhook(others, "opened", 560, body = "Fixes ${mine.fullName}#180"))

        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests").isEmpty).andExpect(jsonPath("$.issues[0].pullRequests").isEmpty)
    }
}
