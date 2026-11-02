package org.studieojavry.coreapi.errorcase.github

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.usecase.ImportGithubIssueUseCase
import org.studieojavry.coreapi.errorcase.github.presentation.web.GithubIssueImportController
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseGithubEventJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseGithubLinkJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseStepGithubLinkJpaRepository
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
 * 실 Postgres + Flyway(V1~V7) + `ddl-auto=validate`. GitHub API 는 [GithubAppApiPort] 목.
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
    @Autowired private lateinit var stepLinkRepo: CaseStepGithubLinkJpaRepository
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

    private fun prItem(
        s: Seeded, number: Int, closes: Int?, state: String = "MERGED", title: String = "fix: close ResultSet",
        alsoCloses: List<GithubItemRef> = emptyList(), headSha: String = "sha-$number",
    ) =
        GithubAppApiPort.PullRequestItem(
            s.repoId,
            GithubPullRequestSnapshot(
                s.repoId, number, title, "https://github.com/${s.fullName}/pull/$number", state, false, "jin",
                headSha, "main", 12, 3, 2, t0.plusSeconds(3600),
                if (state == "MERGED") t0.plusSeconds(7200) else null, if (state != "OPEN") t0.plusSeconds(7200) else null,
                t0.plusSeconds(7200),
            ),
            body = closes?.let { "Fixes #$it" }, defaultBranch = "main",
            closingIssues = listOfNotNull(closes?.let { GithubItemRef(s.repoId, it) }) + alsoCloses,
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

    private fun events(uid: Long, caseId: Long, cursor: String? = null, size: Int? = null) =
        mvc.perform(
            get("/api/v1/error-cases/$caseId/github/events").with(asUser(uid))
                .apply { cursor?.let { param("cursor", it) }; size?.let { param("size", it.toString()) } },
        )

    /** 기록은 실제로 쓰기 유스케이스 트랜잭션 안에서만 일어남 — 테스트도 같은 조건으로. */
    private fun recordEvent(event: CaseGithubEvent) =
        TransactionTemplate(txManager).executeWithoutResult { evidencePort.recordEvent(event) }

    /** P1-b 의 소급 부착이 만드는 것과 같은 모양 — 이미 붙은 PR 연결을 "이 Issue 를 닫는 PR 이라 붙음" 으로 바꾼다. */
    private fun markViaIssue(linkId: Long, issueRepoId: Long, issueNumber: Int) {
        val link = evidencePort.findLinkById(linkId)!!
        link.linkSource = CaseGithubLink.LinkSource.VIA_ISSUE
        link.viaIssueRepoId = issueRepoId
        link.viaIssueNumber = issueNumber
        evidencePort.saveLink(link)
    }

    private fun createStep(uid: Long, caseId: Long, title: String): Long {
        val res = mvc.perform(
            post("/api/v1/error-cases/$caseId/steps").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mapOf("title" to title, "status" to "IN_PROGRESS"))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return om.readTree(res)["step"]["id"].asLong()
    }

    private fun setStepLinks(uid: Long, caseId: Long, stepId: Long, vararg linkIds: Long) = mvc.perform(
        put("/api/v1/error-cases/$caseId/github/steps/$stepId/links").with(asUser(uid))
            .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mapOf("linkIds" to linkIds.toList()))),
    )

    private fun linkIdFrom(res: org.springframework.test.web.servlet.ResultActions): Long =
        om.readTree(res.andReturn().response.contentAsString)["linkId"].asLong()

    private fun linkIdOf(uid: Long, caseId: Long, field: String, number: Int): Long =
        om.readTree(evidence(uid, caseId).andReturn().response.contentAsString)[field]
            .first { it["number"].asInt() == number }["linkId"].asLong()

    // ── Issue 로 Case 만들기: 기본 꺼짐 ─────────────────────────
    @Test
    fun `Issue 로 Case 만들기는 기본 꺼짐 - 빈 없음, 미리보기·생성 요청이 성공하지 않고 GitHub 도 안 부름`() {
        val me = 140L
        val s = seedRepo(me, null)
        assertTrue(context.getBeanNamesForType(ImportGithubIssueUseCase::class.java).isEmpty(), "유스케이스 빈 없음")
        assertTrue(context.getBeanNamesForType(GithubIssueImportController::class.java).isEmpty(), "컨트롤러 빈 없음")
        Mockito.clearInvocations(githubApi)

        val url = "https://github.com/${s.fullName}/issues/42"
        val preview = mvc.perform(get("/api/v1/error-cases/github-issue-preview").with(asUser(me)).param("url", url))
            .andReturn().response.status
        val create = mvc.perform(
            post("/api/v1/error-cases/from-github-issue").with(asUser(me))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mapOf("url" to url, "visibility" to "PRIVATE"))),
        ).andReturn().response.status
        assertTrue(preview >= 400, "미리보기 $preview")
        assertTrue(create >= 400, "생성 $create")
        Mockito.verifyNoInteractions(githubApi)
    }

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
            .andExpect(jsonPath("$.ungroupedPullRequests[0].number").value(153))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].state").value("MERGED"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].repo").value(s.fullName))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.status").value("FAILURE"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.failed[0]").value("integration-test"))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci.stale").value(false))
            .andExpect(jsonPath("$.syncStopped").value(false))
            .andExpect(jsonPath("$.events").doesNotExist())
            .andExpect(jsonPath("$.activity.events[*].type").value(org.hamcrest.Matchers.containsInAnyOrder(
                "PR_OPENED", "PR_MERGED", "CI_COMPLETED", "PR_LINKED",
            )))
            .andExpect(jsonPath("$.activity.hasNext").value(false))
            .andExpect(jsonPath("$.activity.totalCount").value(4))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].closesIssues[0].number").value(142))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].closesIssues[0].inCase").value(false))
        events(me, caseId)
            .andExpect(jsonPath("$.events[*].type").value(org.hamcrest.Matchers.containsInAnyOrder(
                "PR_OPENED", "PR_MERGED", "CI_COMPLETED", "PR_LINKED",
            )))

        assertEquals(1, ledgerRepo.findAllByPrRepoIdAndPrNumber(s.repoId, 153).count { it.issueNumber == 142 })

        link(me, caseId, "https://github.com/${s.fullName}/pull/153")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(false))
        assertEquals(4, eventRepo.findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId).size, "재연결해도 이벤트 중복 없음")
    }

    @Test
    fun `같은 HEAD 커밋을 쓰는 PR 둘 - CI 이력이 PR 마다 따로 남고, 같은 PR 의 재실행은 그 줄만 갱신`() {
        val me = 103L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        stubItem(s, 48, prItem(s, 48, closes = null, headSha = "sha-shared"))
        stubItem(s, 61, prItem(s, 61, closes = null, headSha = "sha-shared"))
        stubChecks(s, "sha-shared", "build" to "success")

        link(me, caseId, "https://github.com/${s.fullName}/pull/48").andExpect(status().isCreated)
        link(me, caseId, "https://github.com/${s.fullName}/pull/61").andExpect(status().isCreated)

        fun ciRows() = eventRepo.findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId).filter { it.type == "CI_COMPLETED" }
        assertEquals(listOf(48, 61), ciRows().map { it.number }.sorted(), "PR 마다 CI 한 줄 — 뒤 PR 의 CI 가 앞 PR 줄에 합쳐지면 안 됨")
        assertEquals(
            setOf("CI:${s.repoId}:48:sha-shared", "CI:${s.repoId}:61:sha-shared"),
            ciRows().map { it.dedupeKey }.toSet(),
        )
        events(me, caseId)
            .andExpect(jsonPath("$.events[?(@.type=='CI_COMPLETED')].number").value(org.hamcrest.Matchers.containsInAnyOrder(48, 61)))

        // 같은 PR·같은 커밋 CI 재실행(실패로 바뀜) → 새 줄 없이 그 PR 줄만 최신 결과로
        val rerun = CiSummary.of("sha-shared", listOf(CiSummary.Run("build", "completed", "failure", t0.plusSeconds(9000))))!!
        recordEvent(CaseGithubEvent.ciCompleted(caseId, s.repoId, 61, rerun, Instant.now()))
        val after = ciRows()
        assertEquals(2, after.size, "재실행은 줄을 늘리지 않음")
        assertEquals("FAILURE", om.readTree(after.single { it.number == 61 }.data)["conclusion"].asText())
        assertEquals("SUCCESS", om.readTree(after.single { it.number == 48 }.data)["conclusion"].asText(), "다른 PR 줄은 그대로")
    }

    @Test
    fun `V8 - 옛 CI 키(CI_repo_sha)를 PR 번호 포함 키로 바꾸고, 다른 이벤트·이미 새 키인 줄은 그대로, 다시 돌려도 같음`() {
        val me = 104L
        val caseId = createCase(me, "PRIVATE")
        val repoId = seq.incrementAndGet()
        fun insert(type: String, number: Int, data: String, key: String) = jdbc.update(
            """
            INSERT INTO case_github_event (case_id, type, kind, repo_id, number, occurred_at, data, dedupe_key, created_at)
            VALUES (?, ?, 'PULL_REQUEST', ?, ?, now(), ?::jsonb, ?, now())
            """.trimIndent(),
            caseId, type, repoId, number, data, key,
        )
        insert("CI_COMPLETED", 48, """{"sha":"abc123","conclusion":"SUCCESS"}""", "CI:$repoId:abc123")
        insert("CI_COMPLETED", 61, """{"sha":"def456","conclusion":"SUCCESS"}""", "CI:$repoId:61:def456")
        insert("PR_OPENED", 48, "{}", "PR_OPENED:$repoId#48")

        val script = ClassPathResource("db/migration/V8__case_github_event_ci_key_per_pr.sql").getContentAsString(Charsets.UTF_8)
        repeat(2) { jdbc.execute(script) }

        val keys = jdbc.queryForList("SELECT dedupe_key FROM case_github_event WHERE case_id = ?", String::class.java, caseId)
        assertEquals(
            setOf("CI:$repoId:48:abc123", "CI:$repoId:61:def456", "PR_OPENED:$repoId#48"),
            keys.filterNotNull().toSet(),
        )
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
            .andExpect(jsonPath("$.ungroupedPullRequests[0].ci").doesNotExist())
        events(me, caseId).andExpect(jsonPath("$.events[?(@.type=='ISSUE_LINKED')]").isNotEmpty)
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
            .andExpect(jsonPath("$.ungroupedPullRequests").isEmpty)
        events(me, caseId).andExpect(jsonPath("$.events[?(@.type=='LINK_DETACHED')]").isNotEmpty)

        link(me, caseId, "https://github.com/${s.fullName}/pull/170")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.linkId").value(linkId))
        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].number").value(170))

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

        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].title").value("SECRET-TITLE-xyz"))

        val body = evidence(outsider, caseId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ungroupedPullRequests[0].masked").value(true))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].state").value("MERGED"))
            .andExpect(jsonPath("$.maskedCount.PULL_REQUEST").value(1))
            .andReturn().response.contentAsString
        val eventsBody = events(outsider, caseId)
            .andExpect(jsonPath("$.events[?(@.type=='CI_COMPLETED')].data.conclusion").value(org.hamcrest.Matchers.hasItem("FAILURE")))
            .andReturn().response.contentAsString
        listOf("SECRET-TITLE-xyz", s.fullName, "pull/180", "secret-job-name", "sha-180", "\"number\"").forEach {
            assertFalse(body.contains(it), "외부인 응답에 '$it' 가 있으면 안 됨: $body")
            assertFalse(eventsBody.contains(it), "외부인 타임라인에 '$it' 가 있으면 안 됨: $eventsBody")
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

        evidence(owner, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].title").value("TEAM-ONLY-title"))
        evidence(reader, caseId).andExpect(jsonPath("$.ungroupedPullRequests[0].title").value("TEAM-ONLY-title"))

        // 주인이 워크스페이스를 떠남 → Case 는 여전히 열람 가능(PUBLIC)하지만 비공개 근거 상세는 가림
        Mockito.`when`(workspaceQuery.getViewerRole(owner, w)).thenReturn(null)
        val body = evidence(owner, caseId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ungroupedPullRequests[0].masked").value(true))
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
            .andExpect(jsonPath("$.ungroupedPullRequests[0].masked").value(false))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].title").value("fix: close ResultSet"))
    }

    // ── 관계형 묶음 ───────────────────────────────────────────
    @Test
    fun `관계형 - 위치는 경유 Issue 먼저 그다음 닫는 Issue, 직접 연결 PR 도 닫는 Issue 아래(출처는 그대로), Issue 를 떼면 옮겨감`() {
        val me = 131L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        stubItem(s, 440, issueItem(s, 440))
        stubItem(s, 445, issueItem(s, 445, title = "두 번째 Issue"))
        stubItem(s, 441, prItem(s, 441, closes = 440))
        stubItem(s, 442, prItem(s, 442, closes = 440, state = "OPEN", alsoCloses = listOf(GithubItemRef(s.repoId, 445))))
        stubItem(s, 443, prItem(s, 443, closes = null))
        listOf("441", "442", "443").forEach { stubChecks(s, "sha-$it") }
        val issue440 = linkIdFrom(link(me, caseId, "https://github.com/${s.fullName}/issues/440").andExpect(status().isCreated))
        val issue445 = linkIdFrom(link(me, caseId, "https://github.com/${s.fullName}/issues/445").andExpect(status().isCreated))
        listOf(441, 443).forEach { link(me, caseId, "https://github.com/${s.fullName}/pull/$it").andExpect(status().isCreated) }
        // #442 는 #440·#445 둘 다 닫고, #445 를 경유해 자동으로 붙은 것으로
        markViaIssue(linkIdFrom(link(me, caseId, "https://github.com/${s.fullName}/pull/442").andExpect(status().isCreated)), s.repoId, 445)

        evidence(me, caseId)
            .andExpect(jsonPath("$.issues[*].number").value(org.hamcrest.Matchers.contains(440, 445)))
            .andExpect(jsonPath("$.issues[0].pullRequests[*].number").value(org.hamcrest.Matchers.contains(441)))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].linkSource").value("MANUAL"))   // 위치 ≠ 출처
            .andExpect(jsonPath("$.issues[1].pullRequests[*].number").value(org.hamcrest.Matchers.contains(442)))   // 경유 Issue 우선
            .andExpect(jsonPath("$.issues[1].pullRequests[0].closesIssues[*].number").value(org.hamcrest.Matchers.containsInAnyOrder(440, 445)))
            .andExpect(jsonPath("$.issues[1].pullRequests[0].closesIssues[*].inCase").value(org.hamcrest.Matchers.contains(true, true)))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].pullRequests").doesNotExist())
            .andExpect(jsonPath("$.ungroupedPullRequests[*].number").value(org.hamcrest.Matchers.contains(443)))

        // 경유 Issue #445 를 떼면 #442 는 자기가 닫는 다른 Issue #440 아래로
        mvc.perform(delete("/api/v1/error-cases/$caseId/github/links/$issue445").with(asUser(me))).andExpect(status().isNoContent)
        evidence(me, caseId)
            .andExpect(jsonPath("$.issues[0].pullRequests[*].number").value(org.hamcrest.Matchers.contains(441, 442)))
            .andExpect(jsonPath("$.issues[0].pullRequests[1].viaIssue.number").value(445))
            .andExpect(jsonPath("$.issues[0].pullRequests[1].closesIssues[?(@.number==445)].inCase").value(org.hamcrest.Matchers.contains(false)))

        // #440 까지 떼면 붙을 Issue 가 없어 모두 묶이지 않은 PR 로
        mvc.perform(delete("/api/v1/error-cases/$caseId/github/links/$issue440").with(asUser(me))).andExpect(status().isNoContent)
        evidence(me, caseId)
            .andExpect(jsonPath("$.issues").isEmpty)
            .andExpect(jsonPath("$.ungroupedPullRequests[*].number").value(org.hamcrest.Matchers.contains(441, 443, 442)))
    }

    @Test
    fun `관계형 - 비공개 레포 Issue 를 닫는 공개 PR, 외부인에게는 묶지 않고 경유·닫는 Issue 번호·레포도 뺌`() {
        val me = 132L
        val outsider = 133L
        val secret = seedRepo(me, null, isPrivate = true)
        val open = seedRepo(me, null, isPrivate = false)
        val caseId = createCase(me, "PUBLIC")
        stubItem(secret, 450, issueItem(secret, 450, title = "SECRET-ISSUE-title"))
        stubItem(open, 451, prItem(open, 451, closes = null, alsoCloses = listOf(GithubItemRef(secret.repoId, 450))))
        stubChecks(open, "sha-451")
        link(me, caseId, "https://github.com/${secret.fullName}/issues/450").andExpect(status().isCreated)
        markViaIssue(linkIdFrom(link(me, caseId, "https://github.com/${open.fullName}/pull/451").andExpect(status().isCreated)), secret.repoId, 450)

        evidence(me, caseId)
            .andExpect(jsonPath("$.issues[0].pullRequests[0].number").value(451))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].viaIssue.repo").value(secret.fullName))
            .andExpect(jsonPath("$.issues[0].pullRequests[0].closesIssues[0].repo").value(secret.fullName))
            .andExpect(jsonPath("$.ungroupedPullRequests").isEmpty)

        val body = evidence(outsider, caseId)
            .andExpect(jsonPath("$.issues[0].masked").value(true))
            .andExpect(jsonPath("$.issues[0].pullRequests").isEmpty)
            .andExpect(jsonPath("$.ungroupedPullRequests[0].number").value(451))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].masked").value(false))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].viaIssue").doesNotExist())
            .andExpect(jsonPath("$.ungroupedPullRequests[0].closesIssues").isEmpty)
            .andReturn().response.contentAsString
        listOf("SECRET-ISSUE-title", secret.fullName, "\"number\":450").forEach {
            assertFalse(body.contains(it), "외부인 응답에 '$it' 가 있으면 안 됨: $body")
        }
    }

    @Test
    fun `근거 조회는 한 읽기 스냅샷(읽기 전용 REPEATABLE READ) 안에서 근거·활동을 읽음`() {
        val owner = 139L
        val w = 9103L
        val s = seedRepo(owner, w, isPrivate = true)
        Mockito.`when`(workspaceQuery.getViewerRole(owner, w)).thenReturn(WorkspaceRole.ADMIN)
        val caseId = createCase(owner, "PRIVATE", w)
        stubItem(s, 470, prItem(s, 470, closes = null))
        stubChecks(s, "sha-470")
        link(owner, caseId, "https://github.com/${s.fullName}/pull/470").andExpect(status().isCreated)

        // 비공개 열람 판단(워크스페이스 멤버 확인)은 조회 도중에 일어남 — 그 순간의 트랜잭션 상태를 잡는다
        val seen = mutableListOf<Pair<Int?, Boolean>>()
        Mockito.`when`(workspaceQuery.getViewerRole(owner, w)).thenAnswer {
            seen += TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() to
                TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            WorkspaceRole.ADMIN
        }
        evidence(owner, caseId).andExpect(status().isOk).andExpect(jsonPath("$.ungroupedPullRequests[0].masked").value(false))
        events(owner, caseId).andExpect(status().isOk)

        assertTrue(seen.isNotEmpty())
        seen.forEach { (isolation, readOnly) ->
            assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ, isolation)
            assertTrue(readOnly)
        }
    }

    @Test
    fun `근거 조회에 활동 첫 쪽 20건 포함, 그 커서로 이전 활동 API 를 이어 부르면 겹침 없이 나머지`() {
        val me = 138L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        (0 until 25).forEach { i ->
            recordEvent(
                CaseGithubEvent(caseId, CaseGithubEvent.Type.PR_OPENED, GithubRefKind.PULL_REQUEST, s.repoId, 600 + i,
                    t0.plusSeconds(i * 60L), emptyMap(), "TEST-A:$i"),
            )
        }
        val first = om.readTree(evidence(me, caseId).andExpect(status().isOk).andReturn().response.contentAsString)["activity"]
        assertEquals(20, first["events"].size())
        assertEquals(624, first["events"][0]["number"].asInt(), "최신이 먼저")
        assertTrue(first["hasNext"].asBoolean())
        assertEquals(25, first["totalCount"].asInt())

        val rest = om.readTree(events(me, caseId, first["nextCursor"].asText()).andReturn().response.contentAsString)
        assertEquals((604 downTo 600).toList(), rest["events"].map { it["number"].asInt() })
        assertFalse(rest["hasNext"].asBoolean())
        assertTrue(rest["nextCursor"].isNull)
    }

    // ── 근거 타임라인 커서 ────────────────────────────────────
    @Test
    fun `타임라인 커서 - 최신순으로 나눠 받고 겹치거나 빠지는 줄 없음, 같은 시각은 id 로 구분, 잘못된 커서 400`() {
        val me = 134L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        // 25건 — 두 건씩 같은 시각(동순위)이 섞이게
        (0 until 25).forEach { i ->
            recordEvent(
                CaseGithubEvent(caseId, CaseGithubEvent.Type.PR_OPENED, GithubRefKind.PULL_REQUEST, s.repoId, 500 + i,
                    t0.plusSeconds((i / 2) * 60L), emptyMap(), "TEST:$i"),
            )
        }

        val seen = mutableListOf<JsonNodeRow>()
        var cursor: String? = null
        val pageSizes = mutableListOf<Int>()
        do {
            val page = om.readTree(events(me, caseId, cursor, size = 10).andExpect(status().isOk).andReturn().response.contentAsString)
            assertEquals(25, page["totalCount"].asInt())
            pageSizes += page["events"].size()
            page["events"].forEach { seen += JsonNodeRow(it["id"].asLong(), Instant.parse(it["occurredAt"].asText())) }
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asText()
            assertEquals(cursor != null, page["hasNext"].asBoolean())
        } while (cursor != null)

        assertEquals(listOf(10, 10, 5), pageSizes)
        assertEquals(25, seen.map { it.id }.toSet().size, "겹치는 줄 없음")
        assertEquals(seen.sortedWith(compareByDescending<JsonNodeRow> { it.at }.thenByDescending { it.id }), seen, "최신순")

        events(me, caseId, cursor = "not-a-cursor").andExpect(status().isBadRequest)
        events(me, caseId, size = 1000).andExpect(jsonPath("$.events.length()").value(25))
        events(135L, caseId).andExpect(status().isForbidden)
    }

    @Test
    fun `타임라인 - 관련 Issue 는 레포 이름으로, 비공개 Issue 면 외부인에게 빼고 data 에 레포 id 를 남기지 않음`() {
        val me = 136L
        val secret = seedRepo(me, null, isPrivate = true)
        val open = seedRepo(me, null, isPrivate = false)
        val caseId = createCase(me, "PUBLIC")
        recordEvent(
            CaseGithubEvent(caseId, CaseGithubEvent.Type.PR_LINKED, GithubRefKind.PULL_REQUEST, open.repoId, 461, t0,
                mapOf("source" to "VIA_ISSUE", "issueRepoId" to secret.repoId, "issueNumber" to 460), "TEST:via"),
        )

        events(me, caseId)
            .andExpect(jsonPath("$.events[0].repo").value(open.fullName))
            .andExpect(jsonPath("$.events[0].issue.repo").value(secret.fullName))
            .andExpect(jsonPath("$.events[0].issue.number").value(460))
            .andExpect(jsonPath("$.events[0].data.issueRepoId").doesNotExist())
            .andExpect(jsonPath("$.events[0].data.source").value("VIA_ISSUE"))
        val body = events(137L, caseId)
            .andExpect(jsonPath("$.events[0].masked").value(false))
            .andExpect(jsonPath("$.events[0].number").value(461))
            .andExpect(jsonPath("$.events[0].issue").doesNotExist())
            .andReturn().response.contentAsString
        listOf(secret.fullName, "\"number\":460", "issueNumber", "issueRepoId").forEach {
            assertFalse(body.contains(it), "외부인 타임라인에 '$it' 가 있으면 안 됨: $body")
        }
    }

    private data class JsonNodeRow(val id: Long, val at: Instant)

    // ── Step 꼬리표 ───────────────────────────────────────────
    @Test
    fun `Step 꼬리표 - 근거 하나를 여러 Step 에, 통째로 맞추기, 다른 Case 근거·다른 Case Step·쓰기 권한 없음 거부`() {
        val me = 121L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        val otherCase = createCase(me, "PRIVATE")
        stubItem(s, 400, prItem(s, 400, closes = null))
        stubItem(s, 401, issueItem(s, 401))
        stubItem(s, 402, prItem(s, 402, closes = null, title = "other case pr"))
        stubChecks(s, "sha-400")
        stubChecks(s, "sha-402")
        link(me, caseId, "https://github.com/${s.fullName}/pull/400").andExpect(status().isCreated)
        link(me, caseId, "https://github.com/${s.fullName}/issues/401").andExpect(status().isCreated)
        link(me, otherCase, "https://github.com/${s.fullName}/pull/402").andExpect(status().isCreated)
        val pr = linkIdOf(me, caseId, "ungroupedPullRequests", 400)
        val issue = linkIdOf(me, caseId, "issues", 401)
        val foreign = linkIdOf(me, otherCase, "ungroupedPullRequests", 402)
        val step1 = createStep(me, caseId, "풀 크기 올림")
        val step2 = createStep(me, caseId, "재시도 경로 검증")
        val foreignStep = createStep(me, otherCase, "다른 Case 의 Step")

        setStepLinks(me, caseId, step1, pr, issue)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stepId").value(step1))
            .andExpect(jsonPath("$.linkIds.length()").value(2))
        setStepLinks(me, caseId, step2, pr).andExpect(status().isOk)
        evidence(me, caseId)
            .andExpect(jsonPath("$.ungroupedPullRequests[0].stepIds").value(org.hamcrest.Matchers.contains(step1.toInt(), step2.toInt())))
            .andExpect(jsonPath("$.issues[0].stepIds").value(org.hamcrest.Matchers.contains(step1.toInt())))

        // 통째로 맞춤: step1 에서 PR 빠짐, Issue 유지
        setStepLinks(me, caseId, step1, issue).andExpect(status().isOk)
        evidence(me, caseId)
            .andExpect(jsonPath("$.ungroupedPullRequests[0].stepIds").value(org.hamcrest.Matchers.contains(step2.toInt())))
            .andExpect(jsonPath("$.issues[0].stepIds").value(org.hamcrest.Matchers.contains(step1.toInt())))

        setStepLinks(me, caseId, step1, foreign).andExpect(status().isBadRequest)
        setStepLinks(me, caseId, foreignStep, pr).andExpect(status().isNotFound)
        setStepLinks(me, caseId, 987654L, pr).andExpect(status().isNotFound)
        setStepLinks(122L, caseId, step1, pr).andExpect(status().isForbidden)

        // 빈 목록 = 모두 해제
        setStepLinks(me, caseId, step1).andExpect(status().isOk).andExpect(jsonPath("$.linkIds").isEmpty)
        evidence(me, caseId).andExpect(jsonPath("$.issues[0].stepIds").isEmpty)
    }

    @Test
    fun `Step 꼬리표 - 근거를 떼면 사라지고 다시 붙여도 복구 안 됨, Step 삭제·Case 삭제 시 정리`() {
        val me = 123L
        val s = seedRepo(me, null)
        val caseId = createCase(me, "PRIVATE")
        stubItem(s, 410, prItem(s, 410, closes = null))
        stubItem(s, 411, prItem(s, 411, closes = null))
        stubChecks(s, "sha-410")
        stubChecks(s, "sha-411")
        val url410 = "https://github.com/${s.fullName}/pull/410"
        link(me, caseId, url410).andExpect(status().isCreated)
        link(me, caseId, "https://github.com/${s.fullName}/pull/411").andExpect(status().isCreated)
        val pr410 = linkIdOf(me, caseId, "ungroupedPullRequests", 410)
        val pr411 = linkIdOf(me, caseId, "ungroupedPullRequests", 411)
        val step1 = createStep(me, caseId, "시도 1")
        val step2 = createStep(me, caseId, "시도 2")
        setStepLinks(me, caseId, step1, pr410, pr411).andExpect(status().isOk)
        setStepLinks(me, caseId, step2, pr411).andExpect(status().isOk)

        mvc.perform(delete("/api/v1/error-cases/$caseId/github/links/$pr410").with(asUser(me))).andExpect(status().isNoContent)
        assertTrue(stepLinkRepo.findAllByCaseIdOrderByCreatedAt(caseId).none { it.linkId == pr410 })
        link(me, caseId, url410).andExpect(status().isCreated)
        assertEquals(pr410, linkIdOf(me, caseId, "ungroupedPullRequests", 410), "다시 붙이면 같은 연결 행 복구")
        evidence(me, caseId).andExpect(jsonPath("$.ungroupedPullRequests[?(@.number==410)].stepIds[0]").doesNotExist())

        mvc.perform(delete("/api/v1/error-cases/$caseId/steps/$step2").with(asUser(me))).andExpect(status().is2xxSuccessful)
        assertEquals(listOf(step1), stepLinkRepo.findAllByCaseIdOrderByCreatedAt(caseId).map { it.stepId })

        mvc.perform(delete("/api/v1/error-cases/$caseId").with(asUser(me))).andExpect(status().is2xxSuccessful)
        assertTrue(stepLinkRepo.findAllByCaseIdOrderByCreatedAt(caseId).isEmpty())
    }

    @Test
    fun `Step 꼬리표 - 비공개 근거가 가려져도 stepIds 는 내려감(제목 없음)`() {
        val me = 124L
        val s = seedRepo(me, null, isPrivate = true)
        val caseId = createCase(me, "PUBLIC")
        stubItem(s, 420, prItem(s, 420, closes = null, title = "PRIVATE-STEP-title"))
        stubChecks(s, "sha-420")
        link(me, caseId, "https://github.com/${s.fullName}/pull/420").andExpect(status().isCreated)
        val step = createStep(me, caseId, "시도")
        setStepLinks(me, caseId, step, linkIdOf(me, caseId, "ungroupedPullRequests", 420)).andExpect(status().isOk)

        val body = evidence(125L, caseId)
            .andExpect(jsonPath("$.ungroupedPullRequests[0].masked").value(true))
            .andExpect(jsonPath("$.ungroupedPullRequests[0].stepIds").value(org.hamcrest.Matchers.contains(step.toInt())))
            .andReturn().response.contentAsString
        assertFalse(body.contains("PRIVATE-STEP-title"))
        assertFalse(body.contains("\"linkId\""), "가려진 항목엔 연결 id 도 없음: $body")
    }

    @Test
    fun `워크스페이스를 떠난 주인 - 근거 연결·떼기·Step 꼬리표 모두 403, GitHub 조회도 안 함`() {
        val owner = 126L
        val w = 9102L
        Mockito.`when`(workspaceQuery.getViewerRole(owner, w)).thenReturn(WorkspaceRole.ADMIN)
        val s = seedRepo(owner, w, isPrivate = true)
        val caseId = createCase(owner, "PUBLIC", w)
        stubItem(s, 430, prItem(s, 430, closes = null))
        stubChecks(s, "sha-430")
        link(owner, caseId, "https://github.com/${s.fullName}/pull/430").andExpect(status().isCreated)
        val linkId = linkIdOf(owner, caseId, "ungroupedPullRequests", 430)
        val step = createStep(owner, caseId, "시도")

        Mockito.`when`(workspaceQuery.getViewerRole(owner, w)).thenReturn(null)
        Mockito.clearInvocations(githubApi)
        link(owner, caseId, "https://github.com/${s.fullName}/pull/431").andExpect(status().isForbidden)
        Mockito.verifyNoInteractions(githubApi)
        mvc.perform(delete("/api/v1/error-cases/$caseId/github/links/$linkId").with(asUser(owner))).andExpect(status().isForbidden)
        setStepLinks(owner, caseId, step, linkId).andExpect(status().isForbidden)
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
