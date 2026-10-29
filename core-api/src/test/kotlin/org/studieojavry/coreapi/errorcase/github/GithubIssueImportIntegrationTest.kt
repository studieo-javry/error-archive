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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GitHub 연동 P1-b 통합 — Issue 연결 시 닫는 PR 소급 부착, Issue 로 Case 만들기(미리보기·생성).
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
class GithubIssueImportIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        /** 테스트마다 겹치지 않는 GitHub id (컨테이너는 클래스 단위 공유). */
        private val seq = AtomicLong(19_000)

        private val ISSUE_BODY = """
            운영에서 간헐적으로 커넥션 타임아웃이 납니다.

            ```text
            java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.
            	at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
            ```

            재현: 주문 몰릴 때
        """.trimIndent()
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var installations: GithubInstallationRepositoryPort
    @Autowired private lateinit var errorCaseRepo: org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
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

    private fun issueItem(
        s: Seeded,
        number: Int,
        candidates: List<GithubAppApiPort.PullRequestCandidate> = emptyList(),
        title: String = "HikariCP connection timeout in production",
        body: String? = ISSUE_BODY,
        labels: List<String> = listOf("bug", "DB", "x".repeat(40)),
    ) = GithubAppApiPort.IssueItem(
        s.repoId,
        GithubIssueSnapshot(s.repoId, number, title, "https://github.com/${s.fullName}/issues/$number", "OPEN", "reporter", t0, null, t0),
        body = body, labels = labels, pullRequestCandidates = candidates,
    )

    private fun cand(s: Seeded, number: Int) = GithubAppApiPort.PullRequestCandidate(s.repoId, s.fullName, number)

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

    private fun events(uid: Long, caseId: Long) =
        mvc.perform(get("/api/v1/error-cases/$caseId/github/events").with(asUser(uid)).param("size", "100"))


    // ── 소급 부착 ─────────────────────────────────────────────
    @Test
    fun `Issue 연결 - 타임라인 후보 중 이 Issue 를 닫는 PR 만 붙고, 언급만 한 PR·다른 범위 PR 은 제외`() {
        val me = 201L
        val s = seedRepo(me, null)
        val other = seedRepo(999L, null)   // 남의 개인 연결 → 범위 불일치
        val caseId = createCase(me, "PRIVATE")
        stubItem(s, 142, issueItem(s, 142, listOf(cand(s, 300), cand(s, 301), cand(other, 302))))
        stubItem(s, 300, prItem(s, 300, closes = 142))
        stubItem(s, 301, prItem(s, 301, closes = null, state = "OPEN"))
        stubChecks(s, "sha-300", "build" to "success")

        link(me, caseId, "https://github.com/${s.fullName}/issues/142")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.backfilledPullRequests").value(1))

        evidence(me, caseId)
            .andExpect(jsonPath("$.issues[0].number").value(142))
            .andExpect(jsonPath("$.issues[0].pullRequests.length()").value(1))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].number").value(300))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].linkSource").value("VIA_ISSUE"))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].viaIssue.number").value(142))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].ci.status").value("SUCCESS"))
            .andExpect(jsonPath("$.pullRequests").isEmpty)
        events(me, caseId)
            .andExpect(jsonPath("$.events[?(@.type=='PR_MERGED')]").isNotEmpty)
            .andExpect(jsonPath("$.events[?(@.type=='PR_LINKED')].issue.number").value(org.hamcrest.Matchers.contains(142)))
            .andExpect(jsonPath("$.events[?(@.type=='PR_LINKED')].issue.repo").value(org.hamcrest.Matchers.contains(s.fullName)))
        val (otherOwner, otherName) = other.fullName.split('/')
        Mockito.verify(githubApi, Mockito.never()).fetchItem(other.installationId, otherOwner, otherName, 302)
    }

    @Test
    fun `Issue 연결 - 장부에 있는 PR(다른 Case 에서 본 것)도 소급으로 붙음`() {
        val me = 202L
        val s = seedRepo(me, null)
        val caseA = createCase(me, "PRIVATE")
        val caseB = createCase(me, "PRIVATE")
        stubItem(s, 310, prItem(s, 310, closes = 150))
        stubChecks(s, "sha-310")
        link(me, caseA, "https://github.com/${s.fullName}/pull/310").andExpect(status().isCreated)

        stubItem(s, 150, issueItem(s, 150))   // 타임라인 후보 없음 — 장부로만 찾음
        link(me, caseB, "https://github.com/${s.fullName}/issues/150")
            .andExpect(jsonPath("$.backfilledPullRequests").value(1))
        evidence(me, caseB)
            .andExpect(jsonPath("$.issues[0].pullRequests[0].number").value(310))
            .andExpect(jsonPath("$.pullRequests").isEmpty)
        evidence(me, caseA).andExpect(jsonPath("$.pullRequests[0].number").value(310))   // 직접 연결한 Case 에선 최상위 그대로
    }

    @Test
    fun `소급 - 관련 없음으로 뗀 PR 은 다시 붙지 않음`() {
        val me = 203L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        stubItem(s, 160, issueItem(s, 160, listOf(cand(s, 320))))
        stubItem(s, 320, prItem(s, 320, closes = 160))
        stubChecks(s, "sha-320")
        link(me, caseId, "https://github.com/${s.fullName}/issues/160").andExpect(jsonPath("$.backfilledPullRequests").value(1))

        val prLinkId = om.readTree(evidence(me, caseId).andReturn().response.contentAsString)["issues"][0]["pullRequests"][0]["linkId"].asLong()
        mvc.perform(delete("/api/v1/error-cases/$caseId/github/links/$prLinkId").with(asUser(me))).andExpect(status().isNoContent)

        link(me, caseId, "https://github.com/${s.fullName}/issues/160")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.backfilledPullRequests").value(0))
        evidence(me, caseId)
            .andExpect(jsonPath("$.issues[0].pullRequests").isEmpty)
            .andExpect(jsonPath("$.pullRequests").isEmpty)
    }

    // ── Issue 로 Case 만들기 ───────────────────────────────────
    private fun preview(uid: Long, url: String, workspaceId: Long? = null) = mvc.perform(
        get("/api/v1/error-cases/github-issue-preview").with(asUser(uid)).param("url", url)
            .apply { workspaceId?.let { param("workspaceId", it.toString()) } },
    )

    private fun importCase(uid: Long, body: Map<String, Any?>) = mvc.perform(
        post("/api/v1/error-cases/from-github-issue").with(asUser(uid))
            .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)),
    )

    @Test
    fun `미리보기 - 에러 로그 블록만 Error Trace, 예외 추출, 태그는 후보만(업무 라벨·긴 라벨 제외), 비공개 저장소는 PUBLIC 불가, 이미 연결된 Case 안내`() {
        val me = 204L
        val s = seedRepo(me, null, isPrivate = true)
        stubItem(s, 170, issueItem(s, 170))
        val url = "https://github.com/${s.fullName}/issues/170"

        preview(me, url)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("HikariCP connection timeout in production"))
            .andExpect(jsonPath("$.paste").value(org.hamcrest.Matchers.startsWith("java.sql.SQLTransientConnectionException")))
            .andExpect(jsonPath("$.paste").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("재현"))))
            .andExpect(jsonPath("$.snapshotPreview.exceptionClass").value("java.sql.SQLTransientConnectionException"))
            .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.endsWith("> GitHub Issue: ${s.fullName}#170")))
            .andExpect(jsonPath("$.codeBlocks[0].errorLog").value(true))
            .andExpect(jsonPath("$.tags").isEmpty)
            .andExpect(jsonPath("$.tagSuggestions").value(org.hamcrest.Matchers.contains("db")))
            .andExpect(jsonPath("$.severitySuggestion").doesNotExist())
            .andExpect(jsonPath("$.workaroundStep").doesNotExist())
            .andExpect(jsonPath("$.reportedAt").exists())
            .andExpect(jsonPath("$.workspaceId").doesNotExist())
            .andExpect(jsonPath("$.defaultVisibility").value("PRIVATE"))
            .andExpect(jsonPath("$.visibilityOptions").value(org.hamcrest.Matchers.contains("PRIVATE")))
            .andExpect(jsonPath("$.repo.isPrivate").value(true))
            .andExpect(jsonPath("$.alreadyLinkedCases").isEmpty)

        val caseId = createCase(me, "PRIVATE")
        link(me, caseId, url).andExpect(status().isCreated)
        preview(me, url).andExpect(jsonPath("$.alreadyLinkedCases[0].id").value(caseId))
    }

    @Test
    fun `생성 - OPEN Case + Issue 연결(IMPORTED) + 소급, 사용자가 고친 제목 반영`() {
        val me = 205L
        val s = seedRepo(me, null, isPrivate = false)
        stubItem(s, 180, issueItem(s, 180, listOf(cand(s, 330))))
        stubItem(s, 330, prItem(s, 330, closes = 180, state = "OPEN"))
        stubChecks(s, "sha-330", "build" to "success")

        val res = importCase(me, mapOf("url" to "https://github.com/${s.fullName}/issues/180", "title" to "결제 커넥션 타임아웃", "visibility" to "PUBLIC"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.backfilledPullRequests").value(1))
            .andReturn().response.contentAsString
        val caseId = om.readTree(res)["caseId"].asLong()

        mvc.perform(get("/api/v1/error-cases/$caseId").with(asUser(me)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("결제 커넥션 타임아웃"))
            .andExpect(jsonPath("$.visibility").value("PUBLIC"))
        evidence(me, caseId)
            .andExpect(jsonPath("$.issues[0].number").value(180))
            .andExpect(jsonPath("$.issues[0].linkSource").value("IMPORTED"))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].number").value(330))
    }

    @Test
    fun `생성 거부 - 비공개 저장소→PUBLIC 400, PR 주소 400, 남의 개인 연결 403, 범위 다른 workspaceId 403, 워크스페이스 READ 403`() {
        val me = 206L
        val ws = 601L
        val priv = seedRepo(me, null, isPrivate = true)
        val wsRepo = seedRepo(me, ws, isPrivate = true)
        stubItem(priv, 1, issueItem(priv, 1))
        stubItem(wsRepo, 2, issueItem(wsRepo, 2))

        importCase(me, mapOf("url" to "https://github.com/${priv.fullName}/issues/1", "visibility" to "PUBLIC")).andExpect(status().isBadRequest)
        importCase(me, mapOf("url" to "https://github.com/${priv.fullName}/pull/1")).andExpect(status().isBadRequest)
        importCase(207L, mapOf("url" to "https://github.com/${priv.fullName}/issues/1")).andExpect(status().isForbidden)
        importCase(me, mapOf("url" to "https://github.com/${priv.fullName}/issues/1", "workspaceId" to ws)).andExpect(status().isForbidden)

        Mockito.`when`(workspaceQuery.getViewerRole(me, ws)).thenReturn(WorkspaceRole.READ)
        importCase(me, mapOf("url" to "https://github.com/${wsRepo.fullName}/issues/2")).andExpect(status().isForbidden)

        Mockito.`when`(workspaceQuery.getViewerRole(me, ws)).thenReturn(WorkspaceRole.WRITE)
        preview(me, "https://github.com/${wsRepo.fullName}/issues/2")
            .andExpect(jsonPath("$.workspaceId").value(ws))
            .andExpect(jsonPath("$.defaultVisibility").value("WORKSPACE"))
            .andExpect(jsonPath("$.visibilityOptions").value(org.hamcrest.Matchers.contains("WORKSPACE", "PRIVATE")))
        importCase(me, mapOf("url" to "https://github.com/${wsRepo.fullName}/issues/2")).andExpect(status().isCreated)
    }

    @Test
    fun `생성 - 고른 태그·심각도·발생 시각만 반영, 우회 방법으로 첫 Step(IN_PROGRESS), 생략하면 아무것도 자동 반영 안 함`() {
        val me = 208L
        val s = seedRepo(me, null, isPrivate = false)
        val body = ISSUE_BODY + "\n\n### Workaround\n\n풀 크기를 20 으로 올림"
        stubItem(s, 190, issueItem(s, 190, body = body, labels = listOf("bug", "priority: high", "in: db")))
        stubItem(s, 191, issueItem(s, 191))
        val url = "https://github.com/${s.fullName}/issues/190"

        preview(me, url)
            .andExpect(jsonPath("$.tagSuggestions").value(org.hamcrest.Matchers.contains("db")))
            .andExpect(jsonPath("$.severitySuggestion.code").value(2))
            .andExpect(jsonPath("$.severitySuggestion.fromLabel").value("priority: high"))
            .andExpect(jsonPath("$.workaroundStep.title").value("우회 방법 (GitHub Issue #190)"))
            .andExpect(jsonPath("$.workaroundStep.body").value("풀 크기를 20 으로 올림"))

        val res = importCase(
            me,
            mapOf(
                "url" to url, "tags" to listOf("db", "hikari"), "severity" to 2,
                "occurredAt" to "2026-10-27T09:00:00Z", "includeWorkaroundStep" to true,
            ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.workaroundStepId").isNumber)
            .andReturn().response.contentAsString
        val caseId = om.readTree(res)["caseId"].asLong()
        mvc.perform(get("/api/v1/error-cases/$caseId").with(asUser(me)))
            .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.containsInAnyOrder("db", "hikari")))
            .andExpect(jsonPath("$.occurredAt").exists())
        assertEquals(2, errorCaseRepo.findById(caseId)!!.meta.severity?.code, "상세 응답엔 심각도가 없어 저장값으로 확인")
        mvc.perform(get("/api/v1/error-cases/$caseId/steps").with(asUser(me)))
            .andExpect(jsonPath("$[0].title").value("우회 방법 (GitHub Issue #190)"))
            .andExpect(jsonPath("$[0].status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$[0].body").value("풀 크기를 20 으로 올림"))

        // 생략하면: 태그 없음, 심각도·발생 시각 비움, Step 없음
        val plain = om.readTree(
            importCase(me, mapOf("url" to "https://github.com/${s.fullName}/issues/191"))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.workaroundStepId").doesNotExist())
                .andReturn().response.contentAsString,
        )["caseId"].asLong()
        mvc.perform(get("/api/v1/error-cases/$plain").with(asUser(me)))
            .andExpect(jsonPath("$.tags").isEmpty)
            .andExpect(jsonPath("$.occurredAt").doesNotExist())
        assertNull(errorCaseRepo.findById(plain)!!.meta.severity)

        // 우회 방법 섹션 없는 Issue 에 Step 요청 / 범위 밖 심각도 → 400
        importCase(me, mapOf("url" to "https://github.com/${s.fullName}/issues/191", "includeWorkaroundStep" to true)).andExpect(status().isBadRequest)
        importCase(me, mapOf("url" to "https://github.com/${s.fullName}/issues/191", "severity" to 7)).andExpect(status().isBadRequest)
    }
}
