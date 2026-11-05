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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubConnectionSelectionCodec
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * PR2 연결 흐름 — OAuth 후보 조회 → 연결 생성 → 레포 선택 변경 → 공간별 해제.
 * installations/new(Save 비활성 막다른 길)를 거치지 않고, 이미 접근 가능한 설치를 공간에 붙이는 **실제 버그 수정** 검증.
 * GitHub API 는 [GithubAppApiPort] 목으로 대체.
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
class GithubConnectionFlowIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var selectionCodec: GithubConnectionSelectionCodec
    @MockitoBean private lateinit var githubApi: GithubAppApiPort
    @MockitoBean private lateinit var workspaceQuery: WorkspaceQueryPort

    private lateinit var mvc: MockMvc
    private val om = ObjectMapper()

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
        Mockito.reset(githubApi)
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    private fun repo(id: Long, name: String, private: Boolean = false) = GithubAppApiPort.RepositoryInfo(id, name, private)

    private fun stubOAuth(uid: Long, installationId: Long, code: String, repos: List<GithubAppApiPort.RepositoryInfo>) {
        Mockito.`when`(githubApi.exchangeUserCode(code)).thenReturn("utok-$uid")
        Mockito.`when`(githubApi.listUserInstallationIds("utok-$uid")).thenReturn(setOf(installationId))
        Mockito.`when`(githubApi.getInstallation(installationId))
            .thenReturn(GithubAppApiPort.InstallationInfo(installationId, "acme-$installationId", "Organization"))
        Mockito.`when`(githubApi.listInstallationRepositories(installationId)).thenReturn(repos)
    }

    /** authorize-url 엔드포인트에서 서명된 state 를 뽑는다(개인 연결). */
    private fun authorizeState(uid: Long, workspaceId: Long? = null): String {
        val req = get("/api/v1/integrations/github/authorize-url").with(asUser(uid))
        workspaceId?.let { req.param("workspaceId", it.toString()) }
        val url = om.readTree(mvc.perform(req).andExpect(status().isOk).andReturn().response.contentAsString)["url"].asText()
        assert(url.startsWith("https://github.com/login/oauth/authorize?client_id=cid&state=")) { url }
        return url.substringAfter("state=")
    }

    /** connection-candidates 호출 → 지정 설치의 candidateToken 반환. */
    private fun candidateToken(uid: Long, installationId: Long, code: String, workspaceId: Long? = null): String {
        val state = authorizeState(uid, workspaceId)
        val body = mvc.perform(
            post("/api/v1/integrations/github/connection-candidates").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("code" to code, "state" to state))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val candidates = om.readTree(body)["candidates"]
        val node = candidates.first { it["installationId"].asLong() == installationId }
        return node["candidateToken"].asText()
    }

    private fun createConnection(uid: Long, token: String, selected: List<Long>): ResultActions =
        mvc.perform(
            post("/api/v1/integrations/github/connections").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("candidateToken" to token, "selectedRepoIds" to selected))),
        )

    private fun listConnections(uid: Long) =
        om.readTree(
            mvc.perform(get("/api/v1/integrations/github/connections").with(asUser(uid)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        )

    // ── authorize-url ──────────────────────────────────────────
    @Test
    fun `authorize-url - installations_new 아닌 OAuth 인가 URL 을 발급`() {
        val state = authorizeState(uid = 10)
        assert(state.isNotBlank())
    }

    @Test
    fun `authorize-url - 워크스페이스 연결은 ADMIN 만`() {
        mvc.perform(get("/api/v1/integrations/github/authorize-url").param("workspaceId", "9055").with(asUser(10)))
            .andExpect(status().isForbidden)
        Mockito.`when`(workspaceQuery.getViewerRole(10, 9055)).thenReturn(WorkspaceQueryPort.WorkspaceRole.ADMIN)
        mvc.perform(get("/api/v1/integrations/github/authorize-url").param("workspaceId", "9055").with(asUser(10)))
            .andExpect(status().isOk)
    }

    // ── 후보 조회 → 생성 ────────────────────────────────────────
    @Test
    fun `생성 - 후보조회로 설치를 받아 레포 일부만 선택해 연결, code 는 한 번만 교환`() {
        val uid = 9100L
        val inst = 91_000L
        stubOAuth(uid, inst, "c-$inst", listOf(repo(91_001, "acme/api"), repo(91_002, "acme/docs"), repo(91_003, "acme/web")))

        val token = candidateToken(uid, inst, "c-$inst")
        createConnection(uid, token, selected = listOf(91_001, 91_003))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.installationId").value(inst))
            .andExpect(jsonPath("$.workspaceId").doesNotExist())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.selectedRepositories.length()").value(2))
            .andExpect(jsonPath("$.availableRepositories.length()").value(3))

        // 목록에도 선택 2 / 가용 3.
        listConnections(uid).let { arr ->
            val node = arr.first { it["installationId"].asLong() == inst }
            assertEquals(2, node["selectedRepositories"].size())
            assertEquals(3, node["availableRepositories"].size())
        }
        // 후보 조회에서 1회만 교환, 생성은 code 재교환 없음.
        Mockito.verify(githubApi, Mockito.times(1)).exchangeUserCode("c-$inst")
    }

    @Test
    fun `생성 - 이미 같은 공간에 연결돼 있으면 409, 후보엔 alreadyConnected 표시`() {
        val uid = 9200L
        val inst = 92_000L
        stubOAuth(uid, inst, "c-$inst", listOf(repo(92_001, "acme/api")))
        createConnection(uid, candidateToken(uid, inst, "c-$inst"), listOf(92_001)).andExpect(status().isCreated)

        // 다시 후보 조회하면 alreadyConnected=true.
        val state = authorizeState(uid)
        val body = mvc.perform(
            post("/api/v1/integrations/github/connection-candidates").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("code" to "c-$inst", "state" to state))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val node = om.readTree(body)["candidates"].first { it["installationId"].asLong() == inst }
        assertEquals(true, node["alreadyConnected"].asBoolean())

        // 그 토큰으로 또 생성하면 409.
        createConnection(uid, node["candidateToken"].asText(), listOf(92_001)).andExpect(status().isConflict)
    }

    @Test
    fun `생성 - grant 밖 레포를 고르면 400`() {
        val uid = 9300L
        val inst = 93_000L
        stubOAuth(uid, inst, "c-$inst", listOf(repo(93_001, "acme/api")))
        createConnection(uid, candidateToken(uid, inst, "c-$inst"), selected = listOf(93_001, 99_999))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `생성 - 다른 사용자의 후보 토큰이면 400`() {
        val requester = 9400L
        val other = 9401L
        val inst = 94_000L
        // 다른 사용자(other) 용으로 서명된 토큰을 requester 가 사용 시도.
        val forged = selectionCodec.issue(other, null, inst)
        createConnection(requester, forged, selected = emptyList()).andExpect(status().isBadRequest)
    }

    // ── 레포 선택 변경(PUT) ────────────────────────────────────
    @Test
    fun `레포선택 변경 - 교체·전부 해제, grant 밖은 400, 없는 연결은 404`() {
        val uid = 9500L
        val inst = 95_000L
        stubOAuth(uid, inst, "c-$inst", listOf(repo(95_001, "acme/api"), repo(95_002, "acme/docs")))
        createConnection(uid, candidateToken(uid, inst, "c-$inst"), listOf(95_001)).andExpect(status().isCreated)
        val connId = listConnections(uid).first { it["installationId"].asLong() == inst }["connectionId"].asLong()

        // acme/docs 로 교체.
        mvc.perform(
            put("/api/v1/integrations/github/connections/$connId/repositories").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("selectedRepoIds" to listOf(95_002)))),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.selectedRepositories.length()").value(1))
            .andExpect(jsonPath("$.selectedRepositories[0].repoId").value(95_002))

        // 전부 해제.
        mvc.perform(
            put("/api/v1/integrations/github/connections/$connId/repositories").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("selectedRepoIds" to emptyList<Long>()))),
        ).andExpect(status().isOk).andExpect(jsonPath("$.selectedRepositories.length()").value(0))

        // grant 밖 → 400.
        mvc.perform(
            put("/api/v1/integrations/github/connections/$connId/repositories").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("selectedRepoIds" to listOf(99_998)))),
        ).andExpect(status().isBadRequest)

        // 없는 연결 → 404.
        mvc.perform(
            put("/api/v1/integrations/github/connections/88888888/repositories").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("selectedRepoIds" to emptyList<Long>()))),
        ).andExpect(status().isNotFound)
    }

    // ── 해제(DELETE) + 재연결 reactivate ───────────────────────
    @Test
    fun `해제 - 공간 연결만 끊기고 재연결 시 같은 연결을 되살린다(멱등)`() {
        val uid = 9600L
        val inst = 96_000L
        stubOAuth(uid, inst, "c-$inst", listOf(repo(96_001, "acme/api")))
        createConnection(uid, candidateToken(uid, inst, "c-$inst"), listOf(96_001)).andExpect(status().isCreated)
        val connId = listConnections(uid).first { it["installationId"].asLong() == inst }["connectionId"].asLong()

        // 해제 → 204, 목록에서 사라짐.
        mvc.perform(delete("/api/v1/integrations/github/connections/$connId").with(asUser(uid)))
            .andExpect(status().isNoContent)
        assert(listConnections(uid).none { it["installationId"].asLong() == inst })
        // 멱등: 다시 해제해도 204.
        mvc.perform(delete("/api/v1/integrations/github/connections/$connId").with(asUser(uid)))
            .andExpect(status().isNoContent)

        // 재연결 → 같은 connectionId 되살림.
        stubOAuth(uid, inst, "c2-$inst", listOf(repo(96_001, "acme/api")))
        createConnection(uid, candidateToken(uid, inst, "c2-$inst"), listOf(96_001)).andExpect(status().isCreated)
        val reconnId = listConnections(uid).first { it["installationId"].asLong() == inst }["connectionId"].asLong()
        assertEquals(connId, reconnId)
    }

    @Test
    fun `해제 - 남의 개인 연결은 404 로 가린다`() {
        val owner = 9700L
        val stranger = 9701L
        val inst = 97_000L
        stubOAuth(owner, inst, "c-$inst", listOf(repo(97_001, "acme/api")))
        createConnection(owner, candidateToken(owner, inst, "c-$inst"), listOf(97_001)).andExpect(status().isCreated)
        val connId = listConnections(owner).first { it["installationId"].asLong() == inst }["connectionId"].asLong()
        assertNotNull(connId)

        mvc.perform(delete("/api/v1/integrations/github/connections/$connId").with(asUser(stranger)))
            .andExpect(status().isNotFound)
    }
}
