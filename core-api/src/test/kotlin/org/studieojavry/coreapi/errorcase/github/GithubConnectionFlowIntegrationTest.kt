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
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubUserAuthorizationPort
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubConnectionSelectionCodec
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubUserAuthorization
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import java.time.Instant
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
    "core.github.token-encryption-key=jKyPH4UiG09Bk7prFiiAmRnDMhba8qG9X1rKcUq1BwY=",
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
    @Autowired private lateinit var userAuthorizations: GithubUserAuthorizationPort
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

    private fun stubOAuth(
        uid: Long,
        installationId: Long,
        code: String,
        repos: List<GithubAppApiPort.RepositoryInfo>,
        // 기본: 모든 레포에 사용자 admin 권한(개인·워크스페이스 모두 선택 가능). 권한 정책 테스트는 명시 전달.
        userPerms: List<GithubAppApiPort.UserRepositoryInfo> =
            repos.map { GithubAppApiPort.UserRepositoryInfo(it.id, canAdmin = true, canPush = true, canPull = true) },
    ) {
        Mockito.`when`(githubApi.exchangeUserCode(code)).thenReturn(GithubAppApiPort.UserAccessToken("utok-$uid"))
        Mockito.`when`(githubApi.getAuthenticatedUser("utok-$uid"))
            .thenReturn(GithubAppApiPort.GithubUserIdentity(90000 + uid, "user-$uid"))
        Mockito.`when`(githubApi.listUserInstallationIds("utok-$uid")).thenReturn(setOf(installationId))
        Mockito.`when`(githubApi.getInstallation(installationId))
            .thenReturn(GithubAppApiPort.InstallationInfo(installationId, "acme-$installationId", "Organization"))
        Mockito.`when`(githubApi.listInstallationRepositories(installationId)).thenReturn(repos)
        Mockito.`when`(githubApi.listUserInstallationRepositories("utok-$uid", installationId)).thenReturn(userPerms)
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

    private fun connectionOptions(uid: Long, workspaceId: Long? = null) =
        get("/api/v1/integrations/github/connection-options").with(asUser(uid)).let { req ->
            workspaceId?.let { req.param("workspaceId", it.toString()) }
            om.readTree(mvc.perform(req).andExpect(status().isOk).andReturn().response.contentAsString)
        }

    private fun saveToken(uid: Long, githubUserId: Long, token: String) {
        val now = Instant.now()
        userAuthorizations.save(GithubUserAuthorization(uid, githubUserId, "login-$uid", token, now, now))
    }

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

    @Test
    fun `connection-candidates - 교환한 사용자 토큰을 암호화 저장(복호화 왕복)`() {
        stubOAuth(uid = 44, installationId = 7001, code = "code-44", repos = listOf(repo(1, "acme/a")))
        candidateToken(uid = 44, installationId = 7001, code = "code-44")

        val stored = userAuthorizations.findByEaUserId(44)
        assertNotNull(stored)
        assertEquals(90044, stored.githubUserId) // getAuthenticatedUser 로 잡은 GitHub 숫자 id → revoked 웹훅 매칭 키
        assertEquals("utok-44", stored.token) // 어댑터가 복호화해 돌려준 평문(DB 에는 ciphertext+iv 만)
        assertEquals("user-44", stored.githubLogin)
    }

    // ── connection-options (시나리오 ②) ─────────────────────────
    @Test
    fun `connection-options - 저장 토큰 없으면 NOT_AUTHORIZED + authorizeUrl`() {
        val body = connectionOptions(uid = 60)
        assertEquals("NOT_AUTHORIZED", body["status"].asText())
        assert(body["authorizeUrl"].asText().startsWith("https://github.com/login/oauth/authorize?client_id=cid&state="))
        assert(body["installUrl"].isNull)
        assertEquals(0, body["candidates"].size())
    }

    @Test
    fun `connection-options - 접근 가능한 설치가 있으면 VERIFIED_AVAILABLE + 후보(GitHub 재이동 없이)`() {
        // 먼저 후보 조회로 토큰을 저장(= 사용자가 이미 한 번 인가한 상태).
        stubOAuth(uid = 61, installationId = 7100, code = "code-61", repos = listOf(repo(1, "acme/a"), repo(2, "acme/b")))
        candidateToken(uid = 61, installationId = 7100, code = "code-61")

        val body = connectionOptions(uid = 61)
        assertEquals("VERIFIED_AVAILABLE", body["status"].asText())
        assert(body["authorizeUrl"].isNull)
        val candidates = body["candidates"]
        assertEquals(1, candidates.size())
        assertEquals(7100, candidates[0]["installationId"].asLong())
        assert(candidates[0]["candidateToken"].asText().isNotBlank()) // 바로 POST /connections 가능
    }

    @Test
    fun `connection-options - 인가는 됐지만 설치가 없으면 VERIFIED_EMPTY + installUrl`() {
        saveToken(uid = 62, githubUserId = 90062, token = "utok-62")
        Mockito.`when`(githubApi.listUserInstallationIds("utok-62")).thenReturn(emptySet())

        val body = connectionOptions(uid = 62)
        assertEquals("VERIFIED_EMPTY", body["status"].asText())
        assert(body["installUrl"].asText().startsWith("https://github.com/apps/error-archive-test/installations/new?state="))
        assertEquals(0, body["candidates"].size())
    }

    @Test
    fun `connection-options - 저장 토큰이 만료·취소면 폐기하고 NOT_AUTHORIZED`() {
        saveToken(uid = 63, githubUserId = 90063, token = "utok-63")
        Mockito.`when`(githubApi.listUserInstallationIds("utok-63"))
            .thenThrow(GithubApiException("bad credentials", clientError = true))

        val body = connectionOptions(uid = 63)
        assertEquals("NOT_AUTHORIZED", body["status"].asText())
        assertEquals(null, userAuthorizations.findByEaUserId(63)) // 무효 토큰은 폐기
    }

    @Test
    fun `connection-options - 유저토큰과 무관한 4xx(설치 조회)면 토큰 유지하고 TEMPORARY_FAILURE`() {
        saveToken(uid = 64, githubUserId = 90064, token = "utok-64")
        // 유저 토큰 호출(설치 목록)은 성공 → 토큰 유효 확인. 그러나 설치 메타 조회(App JWT)가 4xx.
        Mockito.`when`(githubApi.listUserInstallationIds("utok-64")).thenReturn(setOf(7200L))
        Mockito.`when`(githubApi.getInstallation(7200L))
            .thenThrow(GithubApiException("installation gone", clientError = true))

        val body = connectionOptions(uid = 64)
        assertEquals("TEMPORARY_FAILURE", body["status"].asText())
        assertNotNull(userAuthorizations.findByEaUserId(64)) // 유저 토큰은 유효 → 폐기하지 않음
    }

    // ── connection-options: 토큰 수명(선제 갱신) ────────────────
    /** 만료 임박(이미 만료) + 살아있는 refresh 토큰을 가진 저장 행. */
    private fun saveExpiringToken(uid: Long, githubUserId: Long, token: String, refreshToken: String) {
        val now = Instant.now()
        userAuthorizations.save(
            GithubUserAuthorization(
                eaUserId = uid, githubUserId = githubUserId, githubLogin = "login-$uid",
                token = token, createdAt = now, updatedAt = now,
                refreshToken = refreshToken,
                expiresAt = now.minusSeconds(60), // 이미 만료 → needsRefresh
                refreshTokenExpiresAt = now.plusSeconds(180L * 24 * 3600), // refresh 는 아직 유효
            ),
        )
    }

    @Test
    fun `connection-options - 만료 임박 토큰은 설치 조회 전에 선제 갱신하고 새 토큰을 저장`() {
        saveExpiringToken(uid = 70, githubUserId = 90070, token = "utok-70", refreshToken = "rtok-70")
        val now = Instant.now()
        Mockito.`when`(githubApi.refreshUserToken("rtok-70")).thenReturn(
            GithubAppApiPort.UserAccessToken(
                token = "utok-70b", refreshToken = "rtok-70b",
                expiresAt = now.plusSeconds(8 * 3600), refreshTokenExpiresAt = now.plusSeconds(180L * 24 * 3600),
            ),
        )
        // 갱신된 토큰으로 설치 조회 — 설치 없음(VERIFIED_EMPTY)만으로 "새 토큰으로 호출됐다"를 증명.
        Mockito.`when`(githubApi.listUserInstallationIds("utok-70b")).thenReturn(emptySet())

        val body = connectionOptions(uid = 70)
        assertEquals("VERIFIED_EMPTY", body["status"].asText())
        Mockito.verify(githubApi, Mockito.times(1)).refreshUserToken("rtok-70")
        Mockito.verify(githubApi, Mockito.never()).listUserInstallationIds("utok-70") // 옛 토큰으론 호출 안 함
        // 회전된 토큰·refresh 토큰이 저장됨(다음 갱신 대비).
        val stored = userAuthorizations.findByEaUserId(70)
        assertNotNull(stored)
        assertEquals("utok-70b", stored.token)
        assertEquals("rtok-70b", stored.refreshToken)
    }

    @Test
    fun `connection-options - refresh 토큰이 만료·취소면 폐기하고 NOT_AUTHORIZED`() {
        saveExpiringToken(uid = 71, githubUserId = 90071, token = "utok-71", refreshToken = "rtok-71")
        Mockito.`when`(githubApi.refreshUserToken("rtok-71"))
            .thenThrow(GithubApiException("bad_refresh_token", clientError = true))

        val body = connectionOptions(uid = 71)
        assertEquals("NOT_AUTHORIZED", body["status"].asText())
        assertEquals(null, userAuthorizations.findByEaUserId(71)) // 갱신 불가 → 폐기
        Mockito.verify(githubApi, Mockito.never()).listUserInstallationIds(Mockito.anyString())
    }

    @Test
    fun `connection-options - refresh 토큰이 있어도 만료 전이면 갱신 없이 저장 토큰으로 조회`() {
        val now = Instant.now()
        userAuthorizations.save(
            GithubUserAuthorization(
                eaUserId = 72, githubUserId = 90072, githubLogin = "login-72",
                token = "utok-72", createdAt = now, updatedAt = now,
                refreshToken = "rtok-72",
                expiresAt = now.plusSeconds(8 * 3600), // 여유 충분 → 갱신 불필요
                refreshTokenExpiresAt = now.plusSeconds(180L * 24 * 3600),
            ),
        )
        Mockito.`when`(githubApi.listUserInstallationIds("utok-72")).thenReturn(emptySet())

        val body = connectionOptions(uid = 72)
        assertEquals("VERIFIED_EMPTY", body["status"].asText())
        Mockito.verify(githubApi, Mockito.never()).refreshUserToken(Mockito.anyString())
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
        val forged = selectionCodec.issue(other, null, inst, emptyList())
        createConnection(requester, forged, selected = emptyList()).andExpect(status().isBadRequest)
    }

    @Test
    fun `생성 - 워크스페이스 공유는 그 레포의 admin 권한이 필요(read 만이면 400)`() {
        val uid = 9800L
        val inst = 98_000L
        val wsId = 9808L
        Mockito.`when`(workspaceQuery.getViewerRole(uid, wsId)).thenReturn(WorkspaceQueryPort.WorkspaceRole.ADMIN)
        // admin 가능 레포 1 + read 만 가능 레포 1.
        stubOAuth(
            uid, inst, "c-$inst",
            repos = listOf(repo(98_001, "acme/admin-ok"), repo(98_002, "acme/read-only")),
            userPerms = listOf(
                GithubAppApiPort.UserRepositoryInfo(98_001, canAdmin = true, canPush = true, canPull = true),
                GithubAppApiPort.UserRepositoryInfo(98_002, canAdmin = false, canPush = false, canPull = true),
            ),
        )
        val token = candidateToken(uid, inst, "c-$inst", workspaceId = wsId)
        // read 만 가능한 레포를 워크스페이스에 공유 시도 → 400.
        createConnection(uid, token, selected = listOf(98_002)).andExpect(status().isBadRequest)
        // admin 가능 레포는 성공.
        createConnection(uid, token, selected = listOf(98_001))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.workspaceId").value(wsId))
            .andExpect(jsonPath("$.selectedRepositories.length()").value(1))
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

    @Test
    fun `레포선택 변경 - 워크스페이스에 새 레포 추가 시 repo-admin 재확인(admin 통과, read 만 400)`() {
        val uid = 96_100L
        val inst = 961_000L
        val wsId = 96_108L
        Mockito.`when`(workspaceQuery.getViewerRole(uid, wsId)).thenReturn(WorkspaceQueryPort.WorkspaceRole.ADMIN)
        // A·C 는 admin, B 는 read 만. 셋 다 설치 grant.
        stubOAuth(
            uid, inst, "c-$inst",
            repos = listOf(repo(961_001, "acme/a"), repo(961_002, "acme/b"), repo(961_003, "acme/c")),
            userPerms = listOf(
                GithubAppApiPort.UserRepositoryInfo(961_001, canAdmin = true, canPush = true, canPull = true),
                GithubAppApiPort.UserRepositoryInfo(961_002, canAdmin = false, canPush = false, canPull = true),
                GithubAppApiPort.UserRepositoryInfo(961_003, canAdmin = true, canPush = true, canPull = true),
            ),
        )
        val connId = om.readTree(
            createConnection(uid, candidateToken(uid, inst, "c-$inst", workspaceId = wsId), listOf(961_001))
                .andExpect(status().isCreated).andReturn().response.contentAsString,
        )["connectionId"].asLong()

        // 새로 추가하는 C 는 admin → 통과.
        mvc.perform(
            put("/api/v1/integrations/github/connections/$connId/repositories").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("selectedRepoIds" to listOf(961_001, 961_003)))),
        ).andExpect(status().isOk).andExpect(jsonPath("$.selectedRepositories.length()").value(2))

        // 새로 추가하는 B 는 read 만 → 400(워크스페이스 공유 불가).
        mvc.perform(
            put("/api/v1/integrations/github/connections/$connId/repositories").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("selectedRepoIds" to listOf(961_001, 961_003, 961_002)))),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `레포선택 변경 - 워크스페이스 새 레포인데 저장 토큰 없으면 재연결 요구(403)`() {
        val uid = 97_100L
        val inst = 971_000L
        val wsId = 97_108L
        Mockito.`when`(workspaceQuery.getViewerRole(uid, wsId)).thenReturn(WorkspaceQueryPort.WorkspaceRole.ADMIN)
        stubOAuth(uid, inst, "c-$inst", listOf(repo(971_001, "acme/a"), repo(971_002, "acme/b")))
        val connId = om.readTree(
            createConnection(uid, candidateToken(uid, inst, "c-$inst", workspaceId = wsId), listOf(971_001))
                .andExpect(status().isCreated).andReturn().response.contentAsString,
        )["connectionId"].asLong()

        // 저장 토큰 폐기(예: 인가 취소됨) 후 새 레포 추가 시도 → 재연결 요구.
        userAuthorizations.deleteByGithubUserId(90000 + uid)
        mvc.perform(
            put("/api/v1/integrations/github/connections/$connId/repositories").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("selectedRepoIds" to listOf(971_001, 971_002)))),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GITHUB_REAUTHORIZATION_REQUIRED"))
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

    // ── 저장소 새로고침(refresh-repositories) ──────────────────
    @Test
    fun `새로고침 - 새 grant 레포는 후보로만 반영되고 공간 선택엔 자동 추가되지 않는다`() {
        val uid = 9900L
        val inst = 99_000L
        stubOAuth(uid, inst, "c-$inst", listOf(repo(99_001, "acme/api")))
        createConnection(uid, candidateToken(uid, inst, "c-$inst"), listOf(99_001)).andExpect(status().isCreated)

        // GitHub Configure 에서 grant 확대: acme/web 추가.
        Mockito.`when`(githubApi.listInstallationRepositories(inst))
            .thenReturn(listOf(repo(99_001, "acme/api"), repo(99_002, "acme/web")))

        mvc.perform(post("/api/v1/integrations/github/installations/$inst/refresh-repositories").with(asUser(uid)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.availableRepositories.length()").value(2))
            .andExpect(jsonPath("$.addedRepoIds.length()").value(1))
            .andExpect(jsonPath("$.addedRepoIds[0]").value(99_002))

        // 선택은 여전히 acme/api 1개 — 새 레포는 후보일 뿐 자동 선택되지 않는다.
        listConnections(uid).first { it["installationId"].asLong() == inst }.let {
            assertEquals(1, it["selectedRepositories"].size())
            assertEquals(2, it["availableRepositories"].size())
        }
    }

    @Test
    fun `새로고침 - 접근 가능한 연결이 없는 설치면 404`() {
        mvc.perform(post("/api/v1/integrations/github/installations/90909090/refresh-repositories").with(asUser(9950)))
            .andExpect(status().isNotFound)
    }

    // ── 낙관적 버전(동시 편집 충돌) ────────────────────────────
    @Test
    fun `레포선택 변경 - version 이 증가하고 오래된 expectedVersion 은 409`() {
        val uid = 9850L
        val inst = 98_500L
        stubOAuth(uid, inst, "c-$inst", listOf(repo(98_501, "acme/a"), repo(98_502, "acme/b")))
        createConnection(uid, candidateToken(uid, inst, "c-$inst"), listOf(98_501)).andExpect(status().isCreated)
        val created = listConnections(uid).first { it["installationId"].asLong() == inst }
        val connId = created["connectionId"].asLong()
        val v0 = created["version"].asLong()

        // 올바른 expectedVersion 으로 변경 → 200, version 증가.
        mvc.perform(
            put("/api/v1/integrations/github/connections/$connId/repositories").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("selectedRepoIds" to listOf(98_502), "expectedVersion" to v0))),
        ).andExpect(status().isOk).andExpect(jsonPath("$.version").value(v0 + 1))

        // 이제 낡아버린 같은 expectedVersion 으로 또 변경 → 409.
        mvc.perform(
            put("/api/v1/integrations/github/connections/$connId/repositories").with(asUser(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("selectedRepoIds" to listOf(98_501), "expectedVersion" to v0))),
        ).andExpect(status().isConflict)
    }
}
