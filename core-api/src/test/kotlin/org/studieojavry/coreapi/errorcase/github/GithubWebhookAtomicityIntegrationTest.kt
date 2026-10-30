package org.studieojavry.coreapi.errorcase.github

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubWebhookInboxPort
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent.Type
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PR 웹훅 처리의 원자성 — 한 배달(delivery)의 쓰기(PR 스냅샷·닫는 Issue 장부·자동 부착 연결·타임라인 이벤트)는
 * [GithubWebhookInboxProcessor.processOne] 의 트랜잭션 하나로 **전부 반영되거나 전부 안 된다**.
 * 중간에 실패하면 받은 편지함 줄은 PENDING 으로 남아 재시도되고, 재시도는 같은 결과를 한 번만 남긴다.
 *
 * 장애는 이벤트 기록([GithubEvidenceRepositoryPort.recordEvent])을 특정 종류에서 한 번 실패시켜 만든다 — 스냅샷·장부·연결을
 * 이미 쓴 뒤라서, 앞선 쓰기까지 되돌려지는지를 본다.
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
class GithubWebhookAtomicityIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val seq = AtomicLong(39_000)
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var installations: GithubInstallationRepositoryPort
    @Autowired private lateinit var inbox: GithubWebhookInboxPort
    @Autowired private lateinit var processor: GithubWebhookInboxProcessor
    @Autowired private lateinit var inboxRepo: GithubWebhookInboxJpaRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoSpyBean private lateinit var evidencePort: GithubEvidenceRepositoryPort
    @MockitoBean private lateinit var githubApi: GithubAppApiPort
    @MockitoBean private lateinit var workspaceQuery: WorkspaceQueryPort

    private lateinit var mvc: MockMvc
    private val om = ObjectMapper()
    private val t0 = Instant.parse("2026-10-21T01:00:00Z")

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    private data class Seeded(val installationId: Long, val repoId: Long, val fullName: String)

    private fun seedRepo(installedBy: Long): Seeded {
        val id = seq.incrementAndGet()
        installations.save(GithubInstallation.connect(id, "acme-$id", "Organization", null, installedBy))
        val fullName = "acme-$id/payment-api"
        installations.saveRepositories(listOf(GithubRepository.of(id * 10, id, fullName, true)))
        return Seeded(id, id * 10, fullName)
    }

    private fun stubItem(s: Seeded, number: Int, item: GithubAppApiPort.GithubItem?) {
        val (owner, name) = s.fullName.split('/')
        Mockito.`when`(githubApi.fetchItem(s.installationId, owner, name, number)).thenReturn(item)
    }

    private fun prItem(s: Seeded, number: Int, state: String, closes: GithubItemRef) = GithubAppApiPort.PullRequestItem(
        s.repoId,
        GithubPullRequestSnapshot(
            s.repoId, number, "fix: 주문 발행 재시도", "https://github.com/${s.fullName}/pull/$number", state, false, "jin",
            "sha-$number", "main", 12, 3, 2, t0.plusSeconds(1800),
            if (state == "MERGED") t0.plusSeconds(7200) else null, if (state != "OPEN") t0.plusSeconds(7200) else null,
            if (state == "OPEN") t0.plusSeconds(3600) else t0.plusSeconds(7200),
        ),
        body = "Fixes #${closes.number}", defaultBranch = "main", closingIssues = listOf(closes),
    )

    /** Issue #issueNo 를 붙인 Case. */
    private fun caseWithIssue(me: Long, s: Seeded, issueNo: Int): Long {
        val res = mvc.perform(
            post("/api/v1/error-cases").with(asUser(me)).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("title" to "결제 후 주문 누락", "visibility" to "PRIVATE"))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val caseId = om.readTree(res)["id"].asLong()
        stubItem(
            s, issueNo,
            GithubAppApiPort.IssueItem(
                s.repoId,
                GithubIssueSnapshot(s.repoId, issueNo, "주문 누락", "https://github.com/${s.fullName}/issues/$issueNo", "OPEN", "reporter", t0, null, t0),
                body = "본문", labels = emptyList(),
            ),
        )
        mvc.perform(
            post("/api/v1/error-cases/$caseId/github/links").with(asUser(me)).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("url" to "https://github.com/${s.fullName}/issues/$issueNo"))),
        ).andExpect(status().isCreated)
        return caseId
    }

    /** 배달 1건 적재. 처리는 [process] 로 따로 — 같은 배달을 재시도하기 위해. */
    private fun enqueue(s: Seeded, action: String, number: Int, issueNo: Int): String {
        val payload = om.writeValueAsString(
            mapOf(
                "action" to action,
                "installation" to mapOf("id" to s.installationId),
                "repository" to mapOf("id" to s.repoId, "full_name" to s.fullName),
                "pull_request" to mapOf("number" to number, "title" to "fix: 주문 발행 재시도", "body" to "Fixes #$issueNo", "updated_at" to "2026-10-21T05:00:00Z"),
            ),
        )
        val delivery = UUID.randomUUID().toString()
        inbox.insertIfAbsent(GithubWebhookInboxPort.InboundDelivery(delivery, "pull_request", action, s.installationId, payload))
        return delivery
    }

    private fun process(delivery: String) = processor.processOne(delivery).let { inboxRepo.findById(delivery).orElseThrow() }

    /** [type] 이벤트 기록을 처음 한 번만 실패시킨다(이후는 실제 기록). */
    private fun failOnceOnEvent(type: Type) {
        val failed = AtomicBoolean(false)
        Mockito.doAnswer { inv ->
            val e = inv.getArgument<CaseGithubEvent>(0)
            if (e.type == type && failed.compareAndSet(false, true)) throw IllegalStateException("주입된 장애: $type 기록 실패")
            inv.callRealMethod()
        }.`when`(evidencePort).recordEvent(any(CaseGithubEvent::class.java) ?: placeholder)
    }

    private val placeholder = CaseGithubEvent(0, Type.PR_OPENED, GithubRefKind.PULL_REQUEST, 0, 0, Instant.EPOCH, emptyMap(), "placeholder")

    // ── DB 직접 확인 ──
    private fun prState(s: Seeded, number: Int): String? =
        jdbc.queryForList("SELECT state FROM github_pull_request WHERE repo_id = ? AND number = ?", String::class.java, s.repoId, number).firstOrNull()
    private fun ledgerCount(s: Seeded, number: Int) =
        jdbc.queryForObject("SELECT count(*) FROM github_pr_issue_ref WHERE pr_repo_id = ? AND pr_number = ?", Int::class.java, s.repoId, number)!!
    private fun prLinks(caseId: Long, s: Seeded, number: Int) = jdbc.queryForList(
        "SELECT link_source FROM case_github_link WHERE case_id = ? AND kind = 'PULL_REQUEST' AND repo_id = ? AND number = ?",
        String::class.java, caseId, s.repoId, number,
    )
    private fun prEventTypes(caseId: Long, number: Int) = jdbc.queryForList(
        "SELECT type FROM case_github_event WHERE case_id = ? AND kind = 'PULL_REQUEST' AND number = ? ORDER BY id",
        String::class.java, caseId, number,
    ).map { Type.valueOf(it!!) }

    @Test
    fun `자동 부착 웹훅이 이벤트 기록 중 실패 - 스냅샷·장부·연결·이벤트 모두 안 남고 PENDING, 재시도하면 한 번만 반영`() {
        val me = 401L
        val s = seedRepo(me)
        val caseId = caseWithIssue(me, s, 142)
        val issue = GithubItemRef(s.repoId, 142)
        stubItem(s, 500, prItem(s, 500, "OPEN", issue))

        failOnceOnEvent(Type.PR_OPENED) // 스냅샷·장부·연결(PR_LINKED 기록)까지 쓴 뒤 실패
        val delivery = enqueue(s, "opened", 500, 142)
        val failed = process(delivery)

        assertEquals(InboxStatus.PENDING, failed.status, "실패한 배달은 재시도 대기")
        assertEquals(1, failed.attempts)
        val lastError = assertNotNull(failed.lastError)
        assertTrue("주입된 장애" in lastError, lastError)
        assertEquals(null, prState(s, 500), "PR 스냅샷 없음")
        assertEquals(0, ledgerCount(s, 500), "닫는 Issue 장부 없음")
        assertEquals(emptyList<String?>(), prLinks(caseId, s, 500), "자동 부착 연결 없음")
        assertEquals(emptyList<Type>(), prEventTypes(caseId, 500), "PR_LINKED 포함 이벤트 없음")

        val retried = process(delivery)
        assertEquals(InboxStatus.DONE, retried.status)
        assertEquals("OPEN", prState(s, 500))
        assertEquals(1, ledgerCount(s, 500))
        assertEquals(listOf<String?>("VIA_ISSUE"), prLinks(caseId, s, 500))
        val types = prEventTypes(caseId, 500)
        assertEquals(1, types.count { it == Type.PR_LINKED }, "연결 기록 한 번")
        assertEquals(1, types.count { it == Type.PR_OPENED }, "열림 기록 한 번")
    }

    @Test
    fun `이미 붙은 PR 의 머지 웹훅이 실패 - 스냅샷은 OPEN 그대로·머지 이벤트 없음, 재시도하면 MERGED 와 머지 이벤트 한 번`() {
        val me = 402L
        val s = seedRepo(me)
        val caseId = caseWithIssue(me, s, 143)
        val issue = GithubItemRef(s.repoId, 143)
        stubItem(s, 510, prItem(s, 510, "OPEN", issue))
        assertEquals(InboxStatus.DONE, process(enqueue(s, "opened", 510, 143)).status)
        val before = prEventTypes(caseId, 510)

        stubItem(s, 510, prItem(s, 510, "MERGED", issue))
        failOnceOnEvent(Type.PR_MERGED)
        val delivery = enqueue(s, "closed", 510, 143)
        assertEquals(InboxStatus.PENDING, process(delivery).status)
        assertEquals("OPEN", prState(s, 510), "스냅샷 상태 변경도 되돌려짐")
        assertEquals(before, prEventTypes(caseId, 510), "이벤트 그대로")

        assertEquals(InboxStatus.DONE, process(delivery).status)
        assertEquals("MERGED", prState(s, 510))
        val after = prEventTypes(caseId, 510)
        assertEquals(1, after.count { it == Type.PR_MERGED })
        assertEquals(before + Type.PR_MERGED, after, "머지 이벤트만 추가")
    }
}
