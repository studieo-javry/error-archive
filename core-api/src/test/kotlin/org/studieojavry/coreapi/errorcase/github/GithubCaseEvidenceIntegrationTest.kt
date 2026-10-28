package org.studieojavry.coreapi.errorcase.github

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseGithubEventJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseGithubLinkJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubPrIssueRefJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubPullRequestJpaRepository
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
import kotlin.test.assertTrue

/**
 * GitHub 연동 P1-a 통합 — Case 에 Issue/PR 수동 연결·떼기·근거 조회(가림)·Case 삭제 정리.
 * 실 Postgres + Flyway(V1~V5) + `ddl-auto=validate`. GitHub API 는 [GithubAppApiPort] 목.
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
class GithubCaseEvidenceIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        /** 테스트마다 겹치지 않는 GitHub id (컨테이너는 클래스 단위 공유). */
        private val seq = AtomicLong(9_000)
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var installations: GithubInstallationRepositoryPort
    @Autowired private lateinit var linkRepo: CaseGithubLinkJpaRepository
    @Autowired private lateinit var eventRepo: CaseGithubEventJpaRepository
    @Autowired private lateinit var prRepo: GithubPullRequestJpaRepository
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

    private fun seedRepo(installedBy: Long, workspaceId: Long?, isPrivate: Boolean = true): Seeded {
        val id = seq.incrementAndGet()
        installations.save(GithubInstallation.connect(id, "acme-$id", "Organization", workspaceId, installedBy))
        val fullName = "acme-$id/payment-api"
        installations.saveRepositories(listOf(GithubRepository.of(id * 10, id, fullName, isPrivate)))
        return Seeded(id, id * 10, fullName)
    }

    private fun createCase(uid: Long, visibility: String, workspaceId: Long? = null): Long {
        val body = mapOf("title" to "HikariCP connection timeout", "visibility" to visibility, "workspaceId" to workspaceId)
        val res = mvc.perform(
            post("/api/v1/error-cases").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return om.readTree(res)["id"].asLong()
    }

    private fun issueItem(s: Seeded, number: Int, title: String = "Pool exhausted in prod", state: String = "OPEN") =
        GithubAppApiPort.IssueItem(
            s.repoId,
            GithubIssueSnapshot(s.repoId, number, title, "https://github.com/${s.fullName}/issues/$number", state, "reporter", t0, null, t0),
            body = "```\njava.sql.SQLTransientConnectionException\n```", labels = listOf("bug"),
        )

    private fun prItem(s: Seeded, number: Int, closes: Int?, state: String = "MERGED", title: String = "fix: close ResultSet") =
        GithubAppApiPort.PullRequestItem(
            s.repoId,
            GithubPullRequestSnapshot(
                s.repoId, number, title, "https://github.com/${s.fullName}/pull/$number", state, false, "jin",
                "sha-$number", "main", 12, 3, 2, t0.plusSeconds(3600),
                if (state == "MERGED") t0.plusSeconds(7200) else null, if (state != "OPEN") t0.plusSeconds(7200) else null,
                t0.plusSeconds(7200),
            ),
            body = closes?.let { "Fixes #$it" }, defaultBranch = "main",
            closingIssues = listOfNotNull(closes?.let { GithubItemRef(s.repoId, it) }),
        )

    private fun stubItem(s: Seeded, number: Int, item: GithubAppApiPort.GithubItem?) {
        val (owner, name) = s.fullName.split('/')
        Mockito.`when`(githubApi.fetchItem(s.installationId, owner, name, number)).thenReturn(item)
    }

    private fun stubChecks(s: Seeded, sha: String, vararg runs: Pair<String, String>) {
        val (owner, name) = s.fullName.split('/')
        Mockito.`when`(githubApi.fetchCommitCheckRuns(s.installationId, owner, name, sha))
            .thenReturn(runs.map { CiSummary.Run(it.first, "completed", it.second, t0.plusSeconds(5000)) })
    }

    private fun link(uid: Long, caseId: Long, url: String) = mvc.perform(
        post("/api/v1/error-cases/$caseId/github/links").with(asUser(uid))
            .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mapOf("url" to url))),
    )

    private fun evidence(uid: Long, caseId: Long) =
        mvc.perform(get("/api/v1/error-cases/$caseId/github").with(asUser(uid)))

    // ── 연결 ─────────────────────────────────────────────────
    @Test
    fun `PR 주소 연결 - 스냅샷·CI·장부 저장, 타임라인에 연결·열림·머지·CI, 다시 연결하면 200`() {
        val me = 101L
        val s = seedRepo(installedBy = me, workspaceId = null)
        val caseId = createCase(me, "PRIVATE")
        stubItem(s, 153, prItem(s, 153, closes = 142))
        stubChecks(s, "sha-153", "build" to "success", "integration-test" to "failure")

        link(me, caseId, "https://github.com/${s.fullName}/pull/153/files")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.kind").value("PULL_REQUEST"))
            .andExpect(jsonPath("$.linkSource").value("MANUAL"))
            .andExpect(jsonPath("$.created").value(true))

        evidence(me, caseId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pullRequests[0].number").value(153))
            .andExpect(jsonPath("$.pullRequests[0].state").value("MERGED"))
            .andExpect(jsonPath("$.pullRequests[0].repo").value(s.fullName))
            .andExpect(jsonPath("$.pullRequests[0].ci.status").value("FAILURE"))
            .andExpect(jsonPath("$.pullRequests[0].ci.failed[0]").value("integration-test"))
            .andExpect(jsonPath("$.pullRequests[0].ci.stale").value(false))
            .andExpect(jsonPath("$.events[*].type").value(org.hamcrest.Matchers.containsInAnyOrder(
                "PR_OPENED", "PR_MERGED", "CI_COMPLETED", "PR_LINKED",
            )))
            .andExpect(jsonPath("$.syncStopped").value(false))

        assertEquals(1, ledgerRepo.findAllByPrRepoIdAndPrNumber(s.repoId, 153).count { it.issueNumber == 142 })

        link(me, caseId, "https://github.com/${s.fullName}/pull/153")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(false))
        assertEquals(4, eventRepo.findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId).size, "재연결해도 이벤트 중복 없음")
    }

    @Test
    fun `issues 주소인데 실제로는 PR 이면 PR 로 연결, Issue 는 ISSUE_LINKED`() {
        val me = 102L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        stubItem(s, 160, prItem(s, 160, closes = null, state = "OPEN"))
        stubChecks(s, "sha-160")
        stubItem(s, 142, issueItem(s, 142))

        link(me, caseId, "https://github.com/${s.fullName}/issues/160").andExpect(status().isCreated)
            .andExpect(jsonPath("$.kind").value("PULL_REQUEST"))
        link(me, caseId, "https://github.com/${s.fullName.uppercase()}/issues/142").andExpect(status().isCreated)
            .andExpect(jsonPath("$.kind").value("ISSUE"))

        evidence(me, caseId)
            .andExpect(jsonPath("$.issues[0].title").value("Pool exhausted in prod"))
            .andExpect(jsonPath("$.pullRequests[0].ci").doesNotExist())
            .andExpect(jsonPath("$.events[?(@.type=='ISSUE_LINKED')]").isNotEmpty)
    }

    @Test
    fun `연결 거부 - 잘못된 주소 400, 연결 안 된 레포 403, GitHub 에 없음 404, 쓰기 권한 없음 403`() {
        val me = 103L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PUBLIC")
        stubItem(s, 999, null)

        link(me, caseId, "https://gitlab.com/a/b/issues/1").andExpect(status().isBadRequest)
        link(me, caseId, "https://github.com/someone-else/repo/issues/1").andExpect(status().isForbidden)
        link(me, caseId, "https://github.com/${s.fullName}/issues/999").andExpect(status().isNotFound)
        link(104L, caseId, "https://github.com/${s.fullName}/issues/1").andExpect(status().isForbidden)
    }

    @Test
    fun `범위 일치 - 개인 연결 레포는 워크스페이스 Case 에, 워크스페이스 W1 레포는 W2 Case 에 못 붙임`() {
        val me = 105L
        val w1 = 501L
        val w2 = 502L
        Mockito.`when`(workspaceQuery.getViewerRole(me, w1)).thenReturn(WorkspaceRole.ADMIN)
        Mockito.`when`(workspaceQuery.getViewerRole(me, w2)).thenReturn(WorkspaceRole.ADMIN)
        val personal = seedRepo(me, null)
        val ws1Repo = seedRepo(me, w1)
        val ws2Case = createCase(me, "WORKSPACE", w2)
        val ws1Case = createCase(me, "WORKSPACE", w1)
        stubItem(ws1Repo, 1, issueItem(ws1Repo, 1))

        link(me, ws2Case, "https://github.com/${personal.fullName}/issues/1").andExpect(status().isForbidden)
        link(me, ws2Case, "https://github.com/${ws1Repo.fullName}/issues/1").andExpect(status().isForbidden)
        link(me, ws1Case, "https://github.com/${ws1Repo.fullName}/issues/1").andExpect(status().isCreated)
        Mockito.verify(githubApi, Mockito.never()).fetchItem(Mockito.eq(personal.installationId), anyString(), anyString(), Mockito.anyInt())
    }

    // ── 떼기 ─────────────────────────────────────────────────
    @Test
    fun `떼기 - 목록에서 빠지고 타임라인에 기록, 같은 주소 다시 연결하면 201 로 복구`() {
        val me = 106L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        stubItem(s, 170, prItem(s, 170, closes = null, state = "OPEN"))
        stubChecks(s, "sha-170")

        val linkId = om.readTree(
            link(me, caseId, "https://github.com/${s.fullName}/pull/170").andReturn().response.contentAsString,
        )["linkId"].asLong()

        mvc.perform(delete("/api/v1/error-cases/$caseId/github/links/$linkId").with(asUser(me))).andExpect(status().isNoContent)
        mvc.perform(delete("/api/v1/error-cases/$caseId/github/links/$linkId").with(asUser(me))).andExpect(status().isNoContent)
        evidence(me, caseId)
            .andExpect(jsonPath("$.pullRequests").isEmpty)
            .andExpect(jsonPath("$.events[?(@.type=='LINK_DETACHED')]").isNotEmpty)

        link(me, caseId, "https://github.com/${s.fullName}/pull/170")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.linkId").value(linkId))
        evidence(me, caseId).andExpect(jsonPath("$.pullRequests[0].number").value(170))

        mvc.perform(delete("/api/v1/error-cases/$caseId/github/links/987654").with(asUser(me))).andExpect(status().isNotFound)
    }

    // ── 가림 ─────────────────────────────────────────────────
    @Test
    fun `비공개 레포 근거 - 외부인에게는 제목·주소·레포·번호·CI 실패 이름이 응답에 아예 없음`() {
        val me = 107L
        val outsider = 108L
        val s = seedRepo(me, null, isPrivate = true)
        val caseId = createCase(me, "PUBLIC")
        stubItem(s, 180, prItem(s, 180, closes = null, title = "SECRET-TITLE-xyz"))
        stubChecks(s, "sha-180", "secret-job-name" to "failure")
        link(me, caseId, "https://github.com/${s.fullName}/pull/180").andExpect(status().isCreated)

        evidence(me, caseId).andExpect(jsonPath("$.pullRequests[0].title").value("SECRET-TITLE-xyz"))

        val body = evidence(outsider, caseId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pullRequests[0].masked").value(true))
            .andExpect(jsonPath("$.pullRequests[0].state").value("MERGED"))
            .andExpect(jsonPath("$.maskedCount.PULL_REQUEST").value(1))
            .andExpect(jsonPath("$.events[?(@.type=='CI_COMPLETED')].data.conclusion").value(org.hamcrest.Matchers.hasItem("FAILURE")))
            .andReturn().response.contentAsString
        listOf("SECRET-TITLE-xyz", s.fullName, "pull/180", "secret-job-name", "sha-180", "\"number\"").forEach {
            assertFalse(body.contains(it), "외부인 응답에 '$it' 가 있으면 안 됨: $body")
        }
    }

    @Test
    fun `비공개 레포 근거 - 워크스페이스 Case 는 현재 멤버(READ 포함)만, 주인이어도 탈퇴하면 가림`() {
        val owner = 111L
        val reader = 112L
        val w = 9101L
        Mockito.`when`(workspaceQuery.getViewerRole(owner, w)).thenReturn(WorkspaceRole.ADMIN)
        Mockito.`when`(workspaceQuery.getViewerRole(reader, w)).thenReturn(WorkspaceRole.READ)
        val s = seedRepo(owner, w, isPrivate = true)
        val caseId = createCase(owner, "PUBLIC", w)
        stubItem(s, 195, prItem(s, 195, closes = null, title = "TEAM-ONLY-title"))
        stubChecks(s, "sha-195")
        link(owner, caseId, "https://github.com/${s.fullName}/pull/195").andExpect(status().isCreated)

        evidence(owner, caseId).andExpect(jsonPath("$.pullRequests[0].title").value("TEAM-ONLY-title"))
        evidence(reader, caseId).andExpect(jsonPath("$.pullRequests[0].title").value("TEAM-ONLY-title"))

        // 주인이 워크스페이스를 떠남 → Case 는 여전히 열람 가능(PUBLIC)하지만 비공개 근거 상세는 가림
        Mockito.`when`(workspaceQuery.getViewerRole(owner, w)).thenReturn(null)
        val body = evidence(owner, caseId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pullRequests[0].masked").value(true))
            .andReturn().response.contentAsString
        assertFalse(body.contains("TEAM-ONLY-title"), "탈퇴한 주인에게 비공개 제목이 보이면 안 됨: $body")
    }

    @Test
    fun `공개 레포 근거는 외부인에게도 그대로 보임`() {
        val me = 109L
        val s = seedRepo(me, null, isPrivate = false)
        val caseId = createCase(me, "PUBLIC")
        stubItem(s, 190, prItem(s, 190, closes = null))
        stubChecks(s, "sha-190")
        link(me, caseId, "https://github.com/${s.fullName}/pull/190").andExpect(status().isCreated)

        evidence(110L, caseId)
            .andExpect(jsonPath("$.pullRequests[0].masked").value(false))
            .andExpect(jsonPath("$.pullRequests[0].title").value("fix: close ResultSet"))
    }

    // ── Case 삭제 ─────────────────────────────────────────────
    @Test
    fun `Case 삭제 - 연결·타임라인은 지워지고 PR 스냅샷·장부는 남음`() {
        val me = 111L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        stubItem(s, 200, prItem(s, 200, closes = 199))
        stubChecks(s, "sha-200", "build" to "success")
        link(me, caseId, "https://github.com/${s.fullName}/pull/200").andExpect(status().isCreated)
        assertTrue(eventRepo.findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId).isNotEmpty())

        mvc.perform(delete("/api/v1/error-cases/$caseId").with(asUser(me))).andExpect(status().is2xxSuccessful)

        assertTrue(linkRepo.findAllByCaseIdOrderByLinkedAt(caseId).isEmpty())
        assertTrue(eventRepo.findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId).isEmpty())
        assertTrue(prRepo.findAll().any { it.repoId == s.repoId && it.number == 200 })
        assertTrue(ledgerRepo.findAllByPrRepoIdAndPrNumber(s.repoId, 200).isNotEmpty())
    }

    @Test
    fun `GitHub 연결이 끊긴 레포의 근거는 syncStopped=true`() {
        val me = 112L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        stubItem(s, 210, issueItem(s, 210))
        link(me, caseId, "https://github.com/${s.fullName}/issues/210").andExpect(status().isCreated)

        val repo = installations.findRepository(s.repoId)!!
        repo.detach()
        installations.saveRepositories(listOf(repo))

        evidence(me, caseId)
            .andExpect(jsonPath("$.syncStopped").value(true))
            .andExpect(jsonPath("$.issues[0].number").value(210))
    }
}
