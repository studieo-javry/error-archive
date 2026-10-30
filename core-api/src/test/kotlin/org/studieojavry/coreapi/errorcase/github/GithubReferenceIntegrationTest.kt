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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
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
 * 본문 GitHub 참조 — `#` 자동완성 대상 레포(0·1·2개)·Issue/PR 검색·읽기 화면 조회(범위·가림).
 * 실 Postgres + Flyway. GitHub API 는 [GithubAppApiPort] 목.
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
class GithubReferenceIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val seq = AtomicLong(40_000)
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var installations: GithubInstallationRepositoryPort
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

    private data class Seeded(val installationId: Long, val repoId: Long, val fullName: String)

    /** 설치 하나에 레포 [names] 개. */
    private fun seedInstallation(installedBy: Long, workspaceId: Long?, vararg names: String, isPrivate: Boolean = true): List<Seeded> {
        val id = seq.incrementAndGet()
        installations.save(GithubInstallation.connect(id, "acme-$id", "Organization", workspaceId, installedBy))
        val seeded = names.mapIndexed { i, n -> Seeded(id, id * 10 + i, "acme-$id/$n") }
        installations.saveRepositories(seeded.map { GithubRepository.of(it.repoId, id, it.fullName, isPrivate) })
        return seeded
    }

    private fun createCase(uid: Long, visibility: String, workspaceId: Long? = null): Long {
        val body = mapOf("title" to "결제 승인 후 주문 생성 실패", "visibility" to visibility, "workspaceId" to workspaceId)
        val res = mvc.perform(
            post("/api/v1/error-cases").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return om.readTree(res)["id"].asLong()
    }

    private fun issue(s: Seeded, number: Int, title: String, state: String = "OPEN") = GithubAppApiPort.IssueItem(
        s.repoId,
        GithubIssueSnapshot(s.repoId, number, title, "https://github.com/${s.fullName}/issues/$number", state, "qa", t0, null, t0),
        body = null, labels = emptyList(),
    )

    private fun pr(s: Seeded, number: Int, title: String, state: String = "MERGED") = GithubAppApiPort.PullRequestItem(
        s.repoId,
        GithubPullRequestSnapshot(
            s.repoId, number, title, "https://github.com/${s.fullName}/pull/$number", state, false, "jin",
            "sha-$number", "main", 1, 1, 1, t0, null, null, t0,
        ),
        body = null, defaultBranch = "main", closingIssues = emptyList(),
    )

    private fun stubItem(s: Seeded, number: Int, item: GithubAppApiPort.GithubItem?) {
        val (owner, name) = s.fullName.split('/')
        Mockito.`when`(githubApi.fetchItem(s.installationId, owner, name, number)).thenReturn(item)
    }

    private fun repositories(uid: Long, workspaceId: Long?) = mvc.perform(
        get("/api/v1/integrations/github/reference-repositories").with(asUser(uid))
            .apply { workspaceId?.let { param("workspaceId", it.toString()) } },
    )

    private fun search(uid: Long, workspaceId: Long?, q: String) = mvc.perform(
        get("/api/v1/integrations/github/reference-search").with(asUser(uid)).param("q", q)
            .apply { workspaceId?.let { param("workspaceId", it.toString()) } },
    )

    private fun resolve(uid: Long, caseId: Long, vararg refs: String) = mvc.perform(
        post("/api/v1/error-cases/$caseId/github/references").with(asUser(uid))
            .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mapOf("refs" to refs.toList()))),
    )

    // ── 자동완성 대상 레포 ─────────────────────────────────────
    @Test
    fun `대상 레포 - 워크스페이스는 그 공간 레포만(0·1·2개), 개인은 내가 개인 연결한 것만, 비멤버 403`() {
        val me = 4101L
        val w0 = 4901L
        val w1 = 4902L
        val w2 = 4903L
        listOf(w0, w1, w2).forEach { Mockito.`when`(workspaceQuery.getViewerRole(me, it)).thenReturn(WorkspaceRole.WRITE) }
        seedInstallation(me, w1, "payment-api")
        val two = seedInstallation(me, w2, "payment-infra", "payment-api")
        val mine = seedInstallation(me, null, "my-tools")
        seedInstallation(4102L, null, "someone-elses")

        repositories(me, w0).andExpect(status().isOk).andExpect(jsonPath("$.repositories.length()").value(0))
        repositories(me, w1).andExpect(jsonPath("$.repositories.length()").value(1))
        repositories(me, w2)
            .andExpect(jsonPath("$.repositories.length()").value(2))
            .andExpect(jsonPath("$.repositories[0].fullName").value(two[1].fullName)) // 이름순: payment-api < payment-infra
        repositories(me, null)
            .andExpect(jsonPath("$.repositories.length()").value(1))
            .andExpect(jsonPath("$.repositories[0].fullName").value(mine[0].fullName))
        repositories(4103L, w1).andExpect(status().isForbidden)
    }

    // ── 검색 ─────────────────────────────────────────────────
    @Test
    fun `검색 - 번호는 레포마다 그 번호 자체 + 제목 검색, 레포 이름 → 번호 순`() {
        val me = 4111L
        val w = 4911L
        Mockito.`when`(workspaceQuery.getViewerRole(me, w)).thenReturn(WorkspaceRole.READ)
        val (api, infra) = seedInstallation(me, w, "payment-api", "payment-infra")
        stubItem(api, 95, pr(api, 95, "fix: 결제 재시도 중복 방지"))
        stubItem(infra, 95, issue(infra, 95, "Kafka 재시도 및 DLQ 정책 점검"))
        Mockito.`when`(githubApi.searchItems(api.installationId, listOf(api.fullName, infra.fullName), "95", 8)).thenReturn(
            listOf(
                GithubAppApiPort.SearchHit(infra.fullName, 143, GithubRefKind.ISSUE, "stg 배포 95% 에서 멈춤", "OPEN"),
                GithubAppApiPort.SearchHit(api.fullName, 95, GithubRefKind.PULL_REQUEST, "fix: 결제 재시도 중복 방지", "MERGED"),
            ),
        )

        search(me, w, "95").andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].repo").value(api.fullName))
            .andExpect(jsonPath("$.items[0].number").value(95))
            .andExpect(jsonPath("$.items[0].kind").value("PULL_REQUEST"))
            .andExpect(jsonPath("$.items[0].status").value("MERGED"))
            .andExpect(jsonPath("$.items[1].repo").value(infra.fullName))
            .andExpect(jsonPath("$.items[1].number").value(95))
            .andExpect(jsonPath("$.items[1].kind").value("ISSUE"))
            .andExpect(jsonPath("$.items[2].number").value(143))
    }

    @Test
    fun `검색 - 최근 목록에서 번호 앞자리(#9 → 95·96)·레포 이름·종류로 거르고, 오래된 번호는 그 번호 자체로 찾는다`() {
        val me = 4131L
        val w = 4931L
        Mockito.`when`(workspaceQuery.getViewerRole(me, w)).thenReturn(WorkspaceRole.READ)
        val (api, infra) = seedInstallation(me, w, "payment-api", "payment-infra")
        Mockito.`when`(githubApi.listRecentItems(api.installationId, api.fullName.substringBefore('/'), "payment-api", 100)).thenReturn(
            listOf(
                GithubAppApiPort.SearchHit(api.fullName, 96, GithubRefKind.ISSUE, "결제 승인 중복", "OPEN"),
                GithubAppApiPort.SearchHit(api.fullName, 95, GithubRefKind.PULL_REQUEST, "fix: 결제 재시도 중복 방지", "MERGED"),
                GithubAppApiPort.SearchHit(api.fullName, 19, GithubRefKind.ISSUE, "로그 정리", "CLOSED"),
            ),
        )
        Mockito.`when`(githubApi.listRecentItems(infra.installationId, infra.fullName.substringBefore('/'), "payment-infra", 100))
            .thenThrow(org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException("boom"))
        stubItem(api, 9, issue(api, 9, "초기 설정"))

        // #9 → 9 자체 + 앞자리 9 인 95·96 (19 는 제외), 레포 실패는 그 레포만 빠짐
        search(me, w, "9").andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].number").value(9))
            .andExpect(jsonPath("$.items[1].number").value(95))
            .andExpect(jsonPath("$.items[2].number").value(96))
        // # 만 → 최근 목록 전부(레포 → 번호 순), 검색 API 는 안 부름
        search(me, w, "").andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].number").value(19))
        // 종류(pull_request)·레포 이름(api)으로도 찾는다
        search(me, w, "pull").andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].number").value(95))
        search(me, w, "payment-api").andExpect(jsonPath("$.items.length()").value(3))
        Mockito.verify(githubApi, Mockito.never()).searchItems(api.installationId, listOf(api.fullName, infra.fullName), "", 8)
    }

    @Test
    fun `검색 - 글자는 제목 검색만, 레포 0개면 GitHub 를 부르지 않고 빈 결과, GitHub 오류는 빈 결과`() {
        val me = 4121L
        val wEmpty = 4921L
        val w = 4922L
        Mockito.`when`(workspaceQuery.getViewerRole(me, wEmpty)).thenReturn(WorkspaceRole.READ)
        Mockito.`when`(workspaceQuery.getViewerRole(me, w)).thenReturn(WorkspaceRole.READ)
        val (api) = seedInstallation(me, w, "payment-api")
        Mockito.`when`(githubApi.searchItems(api.installationId, listOf(api.fullName), "결제", 8)).thenReturn(
            listOf(GithubAppApiPort.SearchHit(api.fullName, 95, GithubRefKind.PULL_REQUEST, "fix: 결제 재시도 중복 방지", "MERGED")),
        )
        Mockito.`when`(githubApi.searchItems(api.installationId, listOf(api.fullName), "down", 8))
            .thenThrow(org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException("boom"))

        search(me, w, "결제").andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("fix: 결제 재시도 중복 방지"))
        Mockito.verify(githubApi, Mockito.never()).fetchItem(Mockito.anyLong(), anyString(), anyString(), Mockito.anyInt())

        search(me, wEmpty, "95").andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(0))
        search(me, w, "down").andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(0))
        search(4129L, w, "결제").andExpect(status().isForbidden)
    }

    // ── 읽기 화면 조회 ────────────────────────────────────────
    @Test
    fun `조회 - 같은 공간 레포는 제목·상태, 다른 공간·미연결·없음은 resolved=false, 짧은 #N 은 제외`() {
        val me = 4131L
        val w = 4931L
        val other = 4932L
        Mockito.`when`(workspaceQuery.getViewerRole(me, w)).thenReturn(WorkspaceRole.WRITE)
        Mockito.`when`(workspaceQuery.getViewerRole(me, other)).thenReturn(WorkspaceRole.WRITE)
        val (api) = seedInstallation(me, w, "payment-api")
        val (elsewhere) = seedInstallation(me, other, "other-repo")
        stubItem(api, 61, pr(api, 61, "fix: 주문 이벤트 멱등 키로 중복 생성 방지"))
        stubItem(api, 404, null)
        stubItem(elsewhere, 1, issue(elsewhere, 1, "다른 공간"))
        val caseId = createCase(me, "WORKSPACE", w)

        resolve(me, caseId, "${api.fullName}#61", "${elsewhere.fullName}#1", "nobody/none#3", "${api.fullName}#404", "#95")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.references.length()").value(4))
            .andExpect(jsonPath("$.references[0].resolved").value(true))
            .andExpect(jsonPath("$.references[0].kind").value("PULL_REQUEST"))
            .andExpect(jsonPath("$.references[0].title").value("fix: 주문 이벤트 멱등 키로 중복 생성 방지"))
            .andExpect(jsonPath("$.references[0].status").value("MERGED"))
            .andExpect(jsonPath("$.references[0].url").value("https://github.com/${api.fullName}/pull/61"))
            .andExpect(jsonPath("$.references[1].resolved").value(false))
            .andExpect(jsonPath("$.references[2].resolved").value(false))
            .andExpect(jsonPath("$.references[3].resolved").value(false))
        Mockito.verify(githubApi, Mockito.never()).fetchItem(Mockito.eq(elsewhere.installationId), anyString(), anyString(), Mockito.anyInt())
    }

    @Test
    fun `조회 - 비공개 레포는 공개 Case 를 보는 외부인에게 masked, 제목 없음, GitHub 도 안 부름`() {
        val owner = 4141L
        val outsider = 4142L
        val s = seedInstallation(owner, null, "secret-api", isPrivate = true)[0]
        stubItem(s, 7, issue(s, 7, "TEAM-ONLY-title"))
        val caseId = createCase(owner, "PUBLIC")

        resolve(owner, caseId, "${s.fullName}#7")
            .andExpect(jsonPath("$.references[0].title").value("TEAM-ONLY-title"))
        resolve(outsider, caseId, "${s.fullName}#7")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.references[0].masked").value(true))
            .andExpect(jsonPath("$.references[0].resolved").value(false))
            .andExpect(jsonPath("$.references[0].title").doesNotExist())
        Mockito.verify(githubApi, Mockito.times(1)).fetchItem(Mockito.eq(s.installationId), anyString(), anyString(), Mockito.eq(7))
    }

    @Test
    fun `조회 - 비공개 Case 는 읽기 권한 없으면 403`() {
        val owner = 4151L
        val caseId = createCase(owner, "PRIVATE")
        resolve(4152L, caseId, "a/b#1").andExpect(status().isForbidden)
    }
}
