package org.studieojavry.coreapi.errorcase.github

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubWebhookInboxPort
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent.Type
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseGithubEventJpaRepository
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort.WorkspaceRole
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

/**
 * 멀티-공간 — 한 설치의 레포를 개인 + 워크스페이스에 **동시 연결**하고, 같은 Issue 를 두 공간 Case 에 각각 연결했을 때
 * 웹훅 반영이 **공간별 게이트**(활성 연결 + 레포 선택)를 따르는지 검증. 보안 핵심: 한 공간에서 떨어진 레포의
 * 이벤트가 다른 공간에 새지 않아야 한다.
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
    "core.github.token-encryption-key=jKyPH4UiG09Bk7prFiiAmRnDMhba8qG9X1rKcUq1BwY=",
    "core.github.inbox.poll-delay-ms=3600000",
])
class GithubMultiSpaceFanoutIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val seq = AtomicLong(71_000)
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var installations: GithubInstallationRepositoryPort
    @Autowired private lateinit var connections: GithubConnectionRepositoryPort
    @Autowired private lateinit var inbox: GithubWebhookInboxPort
    @Autowired private lateinit var processor: org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxProcessor
    @Autowired private lateinit var eventRepo: CaseGithubEventJpaRepository
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

    private data class Seeded(val installationId: Long, val repoId: Long, val fullName: String) {
        val owner get() = fullName.substringBefore('/')
        val name get() = fullName.substringAfter('/')
    }

    private fun seedInstallation(installedBy: Long): Seeded {
        val id = seq.incrementAndGet()
        installations.save(GithubInstallation.connect(id, "acme-$id", "Organization", installedBy))
        val fullName = "acme-$id/payment-api"
        installations.saveRepositories(listOf(GithubRepository.of(id * 10, id, fullName, true)))
        return Seeded(id, id * 10, fullName)
    }

    private fun createCase(uid: Long, visibility: String, workspaceId: Long? = null): Long {
        val body = mapOf("title" to "결제 후 주문 누락", "visibility" to visibility, "workspaceId" to workspaceId)
        val res = mvc.perform(
            post("/api/v1/error-cases").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return om.readTree(res)["id"].asLong()
    }

    private fun issueItem(s: Seeded, number: Int, state: String, at: Instant) = GithubAppApiPort.IssueItem(
        s.repoId,
        GithubIssueSnapshot(
            s.repoId, number, "주문 누락", "https://github.com/${s.fullName}/issues/$number", state, "reporter", t0,
            if (state == "CLOSED") at else null, at,
        ),
        body = "본문", labels = emptyList(),
    )

    private fun stubIssueFetch(s: Seeded, number: Int, state: String = "OPEN", at: Instant = t0) {
        Mockito.`when`(githubApi.fetchItem(s.installationId, s.owner, s.name, number)).thenReturn(issueItem(s, number, state, at))
    }

    private fun link(uid: Long, caseId: Long, s: Seeded, number: Int) = mvc.perform(
        post("/api/v1/error-cases/$caseId/github/links").with(asUser(uid))
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(mapOf("url" to "https://github.com/${s.fullName}/issues/$number"))),
    )

    private fun deliverIssue(action: String, s: Seeded, number: Int, at: Instant) {
        // 처리기가 다시 조회할 때 쓸 스냅샷(상태) 준비.
        val state = if (action == "closed") "CLOSED" else "OPEN"
        Mockito.`when`(githubApi.fetchItem(s.installationId, s.owner, s.name, number)).thenReturn(issueItem(s, number, state, at))
        val payload = om.writeValueAsString(
            mapOf(
                "action" to action,
                "installation" to mapOf("id" to s.installationId),
                "repository" to mapOf("id" to s.repoId, "full_name" to s.fullName),
                "issue" to mapOf("number" to number, "updated_at" to at.toString()),
            ),
        )
        val delivery = UUID.randomUUID().toString()
        inbox.insertIfAbsent(GithubWebhookInboxPort.InboundDelivery(delivery, "issues", action, s.installationId, payload))
        processor.processOne(delivery)
    }

    private fun eventTypes(caseId: Long) =
        eventRepo.findAllByCaseIdOrderByOccurredAtAscIdAsc(caseId).map { Type.valueOf(it.type) }

    private fun closedCount(caseId: Long) = eventTypes(caseId).count { it == Type.ISSUE_CLOSED }

    @Test
    fun `같은 설치 레포를 개인+워크스페이스 동시 연결 → 이슈 웹훅이 두 공간 모두에 반영`() {
        val userA = 71_001L
        val ws = 72_001L
        Mockito.`when`(workspaceQuery.getViewerRole(userA, ws)).thenReturn(WorkspaceRole.ADMIN)

        val s = seedInstallation(userA)
        // 한 설치를 두 공간에 연결하고 둘 다 레포 선택.
        wireGithubConnection(connections, s.installationId, null, userA, listOf(s.repoId))
        wireGithubConnection(connections, s.installationId, ws, userA, listOf(s.repoId))

        val personalCase = createCase(userA, "PRIVATE")
        val wsCase = createCase(userA, "WORKSPACE", ws)
        stubIssueFetch(s, 10)
        link(userA, personalCase, s, 10).andExpect(status().isCreated)
        link(userA, wsCase, s, 10).andExpect(status().isCreated)

        deliverIssue("closed", s, 10, t0.plusSeconds(60))

        // 두 공간 모두 ISSUE_CLOSED 반영(링크 시 생긴 ISSUE_LINKED 와 함께).
        assertEquals(1, closedCount(personalCase))
        assertEquals(1, closedCount(wsCase))
    }

    @Test
    fun `워크스페이스에서 레포 선택 해제 → 그 공간엔 반영 안 되고 개인은 계속`() {
        val userA = 71_101L
        val ws = 72_101L
        Mockito.`when`(workspaceQuery.getViewerRole(userA, ws)).thenReturn(WorkspaceRole.ADMIN)

        val s = seedInstallation(userA)
        wireGithubConnection(connections, s.installationId, null, userA, listOf(s.repoId))
        wireGithubConnection(connections, s.installationId, ws, userA, listOf(s.repoId))

        val personalCase = createCase(userA, "PRIVATE")
        val wsCase = createCase(userA, "WORKSPACE", ws)
        stubIssueFetch(s, 20)
        link(userA, personalCase, s, 20).andExpect(status().isCreated)
        link(userA, wsCase, s, 20).andExpect(status().isCreated)

        // 워크스페이스 연결에서 레포 선택 해제.
        val wsConnId = connections.findActive(s.installationId, GithubSpace.Workspace(ws))!!.connectionId!!
        connections.replaceSelectedRepos(wsConnId, emptyList(), Instant.now())

        deliverIssue("closed", s, 20, t0.plusSeconds(60))

        // 개인 공간엔 반영, 워크스페이스엔 ISSUE_CLOSED 없음(선택 해제로 반영 멈춤 — 기존 ISSUE_LINKED 근거는 유지).
        assertEquals(1, closedCount(personalCase))
        assertEquals(0, closedCount(wsCase))
    }

    @Test
    fun `레포 선택 해제 후 근거 조회 - 그 공간엔 항목이 마스킹되고 syncStopped(공간 경계)`() {
        val userA = 71_201L
        val ws = 72_201L
        Mockito.`when`(workspaceQuery.getViewerRole(userA, ws)).thenReturn(WorkspaceRole.ADMIN)

        val s = seedInstallation(userA)
        wireGithubConnection(connections, s.installationId, ws, userA, listOf(s.repoId))
        val wsCase = createCase(userA, "WORKSPACE", ws)
        stubIssueFetch(s, 30)
        link(userA, wsCase, s, 30).andExpect(status().isCreated)

        // 선택 중: 워크스페이스 멤버에게 비공개 레포 내용이 보인다.
        mvc.perform(get("/api/v1/error-cases/$wsCase/github").with(asUser(userA)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.syncStopped").value(false))
            .andExpect(jsonPath("$.issues[0].masked").value(false))
            .andExpect(jsonPath("$.issues[0].title").value("주문 누락"))

        // 이 공간에서 레포 선택 해제(링크는 유지 — 의도된 설계).
        val connId = connections.findActive(s.installationId, GithubSpace.Workspace(ws))!!.connectionId!!
        connections.replaceSelectedRepos(connId, emptyList(), Instant.now())

        // 재조회: 멤버여도 더 이상 선택하지 않는 레포의 (전역 스냅샷) 내용은 마스킹 + syncStopped.
        mvc.perform(get("/api/v1/error-cases/$wsCase/github").with(asUser(userA)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.syncStopped").value(true))
            .andExpect(jsonPath("$.issues[0].masked").value(true))
            .andExpect(jsonPath("$.issues[0].title").doesNotExist())
    }
}
