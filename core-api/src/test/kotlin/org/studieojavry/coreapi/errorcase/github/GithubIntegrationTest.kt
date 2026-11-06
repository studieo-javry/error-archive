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
import org.studieojavry.coreapi.errorcase.github.application.port.GithubUserAuthorizationPort
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubUserAuthorization
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubWebhookSignatureVerifier
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxEntity.InboxStatus
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxProcessor
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GitHub 연동 P0 통합 — 실 Postgres 에 Flyway(V1~V4) + `ddl-auto=validate` 로 엔티티↔스키마 정합까지 검증.
 * GitHub API 는 [GithubAppApiPort] 목으로 대체.
 *
 * 커버: 웹훅 서명/멱등, 설치 연결(본인 state·설치 소유·중복 연결), inbox 처리(레포 추가/제거·비공개 전환·순서 역전·설치 삭제·미지원 이벤트).
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
    // 스케줄러가 테스트 중 끼어들지 않게 — 처리는 processOne 으로 직접 구동.
    "core.github.inbox.poll-delay-ms=3600000",
])
class GithubIntegrationTest {

    companion object {
        const val WEBHOOK_SECRET = "whsec"   // @TestPropertySource 의 webhook-secret 과 동일

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var inboxRepo: GithubWebhookInboxJpaRepository
    @Autowired private lateinit var processor: GithubWebhookInboxProcessor
    @Autowired private lateinit var connections: GithubConnectionRepositoryPort
    @Autowired private lateinit var userAuthorizations: GithubUserAuthorizationPort
    @MockitoBean private lateinit var githubApi: GithubAppApiPort
    @MockitoBean private lateinit var workspaceQuery: WorkspaceQueryPort

    private lateinit var mvc: MockMvc
    private val om = ObjectMapper()

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    // ── helpers ────────────────────────────────────────────────
    private fun issueState(uid: Long): String {
        val body = mvc.perform(get("/api/v1/integrations/github/install-url").with(asUser(uid)))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val url = om.readTree(body)["url"].asText()
        assert(url.startsWith("https://github.com/apps/error-archive-test/installations/new?state=")) { url }
        return url.substringAfter("state=")
    }

    private fun stubGithub(installationId: Long, code: String, repos: List<GithubAppApiPort.RepositoryInfo>) {
        Mockito.`when`(githubApi.exchangeUserCode(code)).thenReturn("utok-$code")
        Mockito.`when`(githubApi.listUserInstallationIds("utok-$code")).thenReturn(setOf(installationId))
        Mockito.`when`(githubApi.getInstallation(installationId))
            .thenReturn(GithubAppApiPort.InstallationInfo(installationId, "acme-$installationId", "Organization"))
        Mockito.`when`(githubApi.listInstallationRepositories(installationId)).thenReturn(repos)
    }

    private fun complete(uid: Long, installationId: Long, code: String, state: String) =
        mvc.perform(
            post("/api/v1/integrations/github/installations").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("installationId" to installationId, "code" to code, "state" to state))),
        )

    private fun webhook(event: String, payload: String, delivery: String = UUID.randomUUID().toString(), secret: String = WEBHOOK_SECRET) =
        mvc.perform(
            post("/api/v1/integrations/github/webhook")
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", "sha256=" + GithubWebhookSignatureVerifier.sign(secret, payload.toByteArray()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )

    /** 웹훅 수신 → 처리기 1건 구동 → 최종 상태. */
    private fun deliverAndProcess(event: String, payload: String): InboxStatus {
        val delivery = UUID.randomUUID().toString()
        webhook(event, payload, delivery).andExpect(status().isAccepted)
        processor.processOne(delivery)
        return inboxRepo.findById(delivery).orElseThrow().status
    }

    private fun connect(uid: Long, installationId: Long, repos: List<GithubAppApiPort.RepositoryInfo>) {
        val code = "code-$installationId"
        stubGithub(installationId, code, repos)
        complete(uid, installationId, code, issueState(uid)).andExpect(status().isOk)
    }

    // ── 웹훅 수신 ───────────────────────────────────────────────
    @Test
    fun `웹훅 - 서명이 틀리면 401, 맞으면 202 로 inbox 적재, 같은 delivery 재전송은 200 으로 무시`() {
        val payload = """{"zen":"Keep it logically awesome.","hook_id":1}"""
        val delivery = UUID.randomUUID().toString()

        webhook("ping", payload, delivery, secret = "wrong").andExpect(status().isUnauthorized)
        webhook("ping", payload, delivery).andExpect(status().isAccepted)
        webhook("ping", payload, delivery).andExpect(status().isOk)

        val row = inboxRepo.findById(delivery).orElseThrow()
        assertEquals(InboxStatus.PENDING, row.status)
        assertEquals(1, inboxRepo.findAll().count { it.deliveryId == delivery })
    }

    // 연결 API 무인증 401 은 여기서 검증하지 않는다 — core-api 의 401 엔트리포인트(shared-error)가 core-api 클래스패스에 없는
    // oauth2-resource-server 클래스를 참조해 NoClassDefFoundError(기존 이슈, 게이트웨이가 앞에서 401 처리). 별도 보고.
    @Test
    fun `웹훅 - 인증 토큰 없이도 호출 가능`() {
        webhook("ping", "{}").andExpect(status().isAccepted)
    }

    @Test
    fun `연결 API - 게스트 토큰은 403`() {
        mvc.perform(
            get("/api/v1/integrations/github/installations")
                .with(authentication(InternalAuthentication(7, listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_GUEST")), "test-caller"))),
        ).andExpect(status().isForbidden)
    }

    // ── 설치 연결 ───────────────────────────────────────────────
    @Test
    fun `연결 - 본인 state + 설치 소유 확인되면 설치·레포 저장, 목록에 보인다`() {
        connect(
            uid = 7, installationId = 100,
            repos = listOf(
                GithubAppApiPort.RepositoryInfo(1001, "acme/api", isPrivate = true),
                GithubAppApiPort.RepositoryInfo(1002, "acme/docs", isPrivate = false),
            ),
        )

        mvc.perform(get("/api/v1/integrations/github/installations").with(asUser(7)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.installationId == 100)].accountLogin").value("acme-100"))
            .andExpect(jsonPath("$[?(@.installationId == 100)].repositories.length()").value(2))
        // 다른 사용자에겐 안 보임(개인 연결)
        mvc.perform(get("/api/v1/integrations/github/installations").with(asUser(8)))
            .andExpect(jsonPath("$[?(@.installationId == 100)]").isEmpty)
    }

    @Test
    fun `연결 - 남이 시작한 state 로 완료하면 400 (설치 링크 바꿔치기 방어)`() {
        stubGithub(200, "c200", emptyList())
        complete(uid = 8, installationId = 200, code = "c200", state = issueState(7)).andExpect(status().isBadRequest)
    }

    @Test
    fun `연결 - 이 GitHub 계정이 접근 못 하는 installation id 면 403`() {
        stubGithub(300, "c300", emptyList())
        complete(uid = 7, installationId = 999, code = "c300", state = issueState(7)).andExpect(status().isForbidden)
    }

    @Test
    fun `연결 - 같은 설치를 다른 사용자가 각자 개인 공간에 연결(멀티-공간)`() {
        // GitHub 는 계정당 설치 1건만 허용 — 같은 설치를 EA 사용자 둘이 각자 공간에 연결할 수 있어야 한다.
        connect(uid = 7, installationId = 400, repos = emptyList())
        stubGithub(400, "c400-b", emptyList())
        complete(uid = 8, installationId = 400, code = "c400-b", state = issueState(8)).andExpect(status().isOk)
        // 둘 다 자기 목록에서 본다(각자 개인 연결).
        mvc.perform(get("/api/v1/integrations/github/installations").with(asUser(7)))
            .andExpect(jsonPath("$[?(@.installationId == 400)]").isNotEmpty)
        mvc.perform(get("/api/v1/integrations/github/installations").with(asUser(8)))
            .andExpect(jsonPath("$[?(@.installationId == 400)]").isNotEmpty)
    }

    @Test
    fun `연결 - 워크스페이스로 연결은 ADMIN 만 (아니면 403)`() {
        mvc.perform(get("/api/v1/integrations/github/install-url").param("workspaceId", "55").with(asUser(7)))
            .andExpect(status().isForbidden)
        Mockito.`when`(workspaceQuery.getViewerRole(7, 55)).thenReturn(WorkspaceQueryPort.WorkspaceRole.ADMIN)
        mvc.perform(get("/api/v1/integrations/github/install-url").param("workspaceId", "55").with(asUser(7)))
            .andExpect(status().isOk)
    }

    // ── 인가 취소 ───────────────────────────────────────────────
    @Test
    fun `웹훅 github_app_authorization revoked - 그 GitHub 사용자의 저장 토큰을 폐기`() {
        val now = Instant.now()
        userAuthorizations.save(GithubUserAuthorization(eaUserId = 501, githubUserId = 7777, githubLogin = "octo", token = "utok-x", createdAt = now, updatedAt = now))
        userAuthorizations.save(GithubUserAuthorization(eaUserId = 502, githubUserId = 8888, githubLogin = "other", token = "utok-y", createdAt = now, updatedAt = now))
        assertNotNull(userAuthorizations.findByEaUserId(501))

        // installation 없는 payload — sender.id 로 매칭.
        val status = deliverAndProcess("github_app_authorization", """{"action":"revoked","sender":{"id":7777,"login":"octo"}}""")
        assertEquals(InboxStatus.DONE, status)

        assertNull(userAuthorizations.findByEaUserId(501)) // 폐기됨
        assertNotNull(userAuthorizations.findByEaUserId(502)) // 다른 사용자는 그대로
    }

    @Test
    fun `웹훅 github_app_authorization revoked - 저장 토큰 없으면 SKIPPED`() {
        val status = deliverAndProcess("github_app_authorization", """{"action":"revoked","sender":{"id":99999,"login":"nobody"}}""")
        assertEquals(InboxStatus.SKIPPED, status)
    }

    // ── inbox 처리 ───────────────────────────────────────────────
    @Test
    fun `처리 - 레포 추가는 후보로만(자동선택 금지)·제거, 비공개 전환, 늦게 온 옛 이벤트 무시, 설치 삭제`() {
        connect(uid = 9, installationId = 500, repos = listOf(GithubAppApiPort.RepositoryInfo(5001, "acme/a", isPrivate = false)))
        val inst = """"installation":{"id":500}"""

        // 레포 추가 — grant(설치 접근)에만 더해지고, 이 공간의 선택에는 자동 반영되지 않는다(§6).
        assertEquals(InboxStatus.DONE, deliverAndProcess(
            "installation_repositories",
            """{"action":"added",$inst,"repositories_added":[{"id":5002,"full_name":"acme/b","private":false}],"repositories_removed":[]}""",
        ))
        // 선택은 그대로 acme/a 1개 — 새로 grant 된 acme/b 는 후보일 뿐 자동 선택되지 않음.
        mvc.perform(get("/api/v1/integrations/github/installations").with(asUser(9)))
            .andExpect(jsonPath("$[0].repositories.length()").value(1))
            .andExpect(jsonPath("$[0].repositories[0].fullName").value("acme/a"))

        // 사용자가 새 후보 acme/b 를 이 공간에서 명시적으로 선택.
        val connectionId = connections.findActive(500, GithubSpace.Personal(9))!!.connectionId!!
        connections.replaceSelectedRepos(connectionId, listOf(5001L, 5002L), java.time.Instant.now())

        // 레포 제거 — grant 에서 빠지면 선택에 남아 있어도 비활성으로 걸러진다.
        assertEquals(InboxStatus.DONE, deliverAndProcess(
            "installation_repositories",
            """{"action":"removed",$inst,"repositories_added":[],"repositories_removed":[{"id":5001,"full_name":"acme/a"}]}""",
        ))
        // 비공개 전환(10:00) → 그보다 옛날(09:00) publicized 가 늦게 도착 → SKIPPED, 비공개 유지
        assertEquals(InboxStatus.DONE, deliverAndProcess(
            "repository",
            """{"action":"privatized",$inst,"repository":{"id":5002,"full_name":"acme/b","private":true,"updated_at":"2026-10-27T10:00:00Z"}}""",
        ))
        assertEquals(InboxStatus.SKIPPED, deliverAndProcess(
            "repository",
            """{"action":"publicized",$inst,"repository":{"id":5002,"full_name":"acme/b","private":false,"updated_at":"2026-10-27T09:00:00Z"}}""",
        ))
        mvc.perform(get("/api/v1/integrations/github/installations").with(asUser(9)))
            .andExpect(jsonPath("$[0].repositories.length()").value(1))
            .andExpect(jsonPath("$[0].repositories[0].fullName").value("acme/b"))
            .andExpect(jsonPath("$[0].repositories[0].isPrivate").value(true))

        // P1 대상 이벤트는 아직 SKIPPED (행은 보존)
        assertEquals(InboxStatus.SKIPPED, deliverAndProcess("issues", """{"action":"opened",$inst}"""))

        // 설치 삭제 → 목록에서 사라짐
        assertEquals(InboxStatus.DONE, deliverAndProcess("installation", """{"action":"deleted",$inst}"""))
        mvc.perform(get("/api/v1/integrations/github/installations").with(asUser(9)))
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `처리 - 우리와 연결 안 된 설치의 이벤트는 SKIPPED`() {
        assertEquals(InboxStatus.SKIPPED, deliverAndProcess("installation", """{"action":"suspend","installation":{"id":777}}"""))
    }

    @Test
    fun `ACCESS_LOST - grant 에서 빠진 선택은 비활성이고 재허용돼도 자동 복구되지 않는다`() {
        connect(
            uid = 20, installationId = 600,
            repos = listOf(
                GithubAppApiPort.RepositoryInfo(6001, "acme/a", isPrivate = false),
                GithubAppApiPort.RepositoryInfo(6002, "acme/b", isPrivate = false),
            ),
        )
        val inst = """"installation":{"id":600}"""
        val space = GithubSpace.Personal(20)
        val connId = connections.findActive(600, space)!!.connectionId!!
        // 부트스트랩 선택 = grant 전부.
        assertEquals(setOf(6001L, 6002L), connections.selectedRepoIds(connId))
        assertTrue(connections.isRepoSelectedInSpace(space, 6001))

        // 6001 이 grant 에서 빠짐 → 그 선택은 ACCESS_LOST → 실효 사용 중단.
        assertEquals(InboxStatus.DONE, deliverAndProcess(
            "installation_repositories",
            """{"action":"removed",$inst,"repositories_added":[],"repositories_removed":[{"id":6001,"full_name":"acme/a"}]}""",
        ))
        assertFalse(connections.isRepoSelectedInSpace(space, 6001))
        assertEquals(setOf(6002L), connections.selectedRepoIds(connId))

        // 6001 재허용(added) → 여전히 비활성(자동 복구 금지).
        assertEquals(InboxStatus.DONE, deliverAndProcess(
            "installation_repositories",
            """{"action":"added",$inst,"repositories_added":[{"id":6001,"full_name":"acme/a","private":false}],"repositories_removed":[]}""",
        ))
        assertFalse(connections.isRepoSelectedInSpace(space, 6001))
        assertEquals(setOf(6002L), connections.selectedRepoIds(connId))

        // 사용자가 공간에서 다시 명시 선택 → ACTIVE 복귀.
        connections.replaceSelectedRepos(connId, listOf(6001L, 6002L), java.time.Instant.now())
        assertTrue(connections.isRepoSelectedInSpace(space, 6001))
        assertEquals(setOf(6001L, 6002L), connections.selectedRepoIds(connId))
    }
}
