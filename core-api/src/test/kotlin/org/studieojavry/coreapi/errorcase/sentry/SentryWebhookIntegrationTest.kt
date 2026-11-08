package org.studieojavry.coreapi.errorcase.sentry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.errorcase.sentry.application.usecase.SentryWebhookSignatureVerifier
import org.studieojavry.coreapi.errorcase.sentry.domain.model.vo.SentryCandidateStatus
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.inbox.SentryWebhookInboxEntity
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.inbox.SentryWebhookInboxJpaRepository
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.inbox.SentryWebhookInboxProcessor
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa.SentryIssueCaseJpaRepository
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa.SentryProjectLinkJpaRepository
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa.entity.SentryProjectLinkEntity
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "core.sentry.enabled=true",
        "core.sentry.client-secret=test-sentry-secret",
        // 스케줄러가 테스트 중 멋대로 돌지 않도록 폴링 주기를 크게 — 처리는 processOne 으로 직접 구동.
        "core.sentry.inbox.poll-delay-ms=3600000",
    ],
)
class SentryWebhookIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private const val SECRET = "test-sentry-secret"
        private const val PROJECT = "my-proj"
        private const val WORKSPACE_ID = 1L
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var inboxRepo: SentryWebhookInboxJpaRepository
    @Autowired private lateinit var processor: SentryWebhookInboxProcessor
    @Autowired private lateinit var projectLinks: SentryProjectLinkJpaRepository
    @Autowired private lateinit var candidates: SentryIssueCaseJpaRepository

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
        candidates.deleteAll()
        inboxRepo.deleteAll()
        projectLinks.deleteAll()
        projectLinks.save(
            SentryProjectLinkEntity(
                workspaceId = WORKSPACE_ID, sentryOrg = "acme", sentryProject = PROJECT,
                createdBy = 1L, createdAt = Instant.now(),
            ),
        )
    }

    private fun issuePayload(issueId: String, action: String): String =
        """
        {"action":"$action","data":{"issue":{
          "id":"$issueId","shortId":"PROJ-1","title":"TypeError: x is undefined",
          "culprit":"app.foo in handleRequest","level":"error",
          "permalink":"https://sentry.io/organizations/acme/issues/$issueId/",
          "project":{"slug":"$PROJECT"},"metadata":{"type":"TypeError"}}}}
        """.trimIndent()

    private fun post(payload: String, signature: String?): org.springframework.test.web.servlet.ResultActions {
        val body = payload.toByteArray()
        val req = post("/api/v1/integrations/sentry/webhook")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Sentry-Hook-Resource", "issue")
            .content(body)
        if (signature != null) req.header("Sentry-Hook-Signature", signature)
        return mockMvc.perform(req)
    }

    private fun sign(payload: String): String = SentryWebhookSignatureVerifier.sign(SECRET, payload.toByteArray())

    private fun drainInbox() {
        inboxRepo.findAll().forEach { processor.processOne(it.eventKey) }
    }

    @Test
    fun `유효 서명 신규 이슈 → 202 + PROPOSED 후보 생성`() {
        val payload = issuePayload("100", "created")
        post(payload, sign(payload)).andExpect(status().isAccepted)

        assertEquals(1L, inboxRepo.countByStatus(SentryWebhookInboxEntity.InboxStatus.PENDING))
        drainInbox()

        val candidate = candidates.findById("100").orElseThrow()
        assertEquals(SentryCandidateStatus.PROPOSED, candidate.status)
        assertEquals(WORKSPACE_ID, candidate.workspaceId)
        assertEquals(1, candidate.occurrences)
        assertNull(candidate.caseId)
        assertEquals(SentryWebhookInboxEntity.InboxStatus.DONE, inboxRepo.findAll().single().status)
    }

    @Test
    fun `같은 이슈 재수신 → 발생 이력 누적`() {
        val created = issuePayload("200", "created")
        post(created, sign(created)).andExpect(status().isAccepted)
        drainInbox()
        // 같은 이슈의 다른 본문(두 번째 감지) → 다른 event_key 라 새 inbox row
        val again = issuePayload("200", "created").replace("undefined", "null")
        post(again, sign(again)).andExpect(status().isAccepted)
        drainInbox()

        assertEquals(2, candidates.findById("200").orElseThrow().occurrences)
    }

    @Test
    fun `regression 재발 → 발생 이력(연결 Case 없으면 상태 유지)`() {
        val created = issuePayload("300", "created")
        post(created, sign(created)).andExpect(status().isAccepted)
        drainInbox()
        val regression = issuePayload("300", "unresolved")
        post(regression, sign(regression)).andExpect(status().isAccepted)
        drainInbox()

        val candidate = candidates.findById("300").orElseThrow()
        assertEquals(2, candidate.occurrences)
        assertEquals(SentryCandidateStatus.PROPOSED, candidate.status) // caseId 없음 → REOPEN_PROPOSED 아님
    }

    @Test
    fun `같은 본문 재전송 → 200 중복 무시`() {
        val payload = issuePayload("400", "created")
        val sig = sign(payload)
        post(payload, sig).andExpect(status().isAccepted)
        post(payload, sig).andExpect(status().isOk)
        assertEquals(1, inboxRepo.findAll().size)
    }

    @Test
    fun `서명 불일치 → 401`() {
        val payload = issuePayload("500", "created")
        post(payload, "deadbeef").andExpect(status().isUnauthorized)
        post(payload, null).andExpect(status().isUnauthorized)
        assertTrue(inboxRepo.findAll().isEmpty())
    }

    @Test
    fun `미연결 프로젝트 → SKIPPED (후보 없음)`() {
        val payload = issuePayload("600", "created").replace("\"slug\":\"$PROJECT\"", "\"slug\":\"unknown-proj\"")
        post(payload, sign(payload)).andExpect(status().isAccepted)
        drainInbox()

        assertTrue(candidates.findById("600").isEmpty)
        assertEquals(SentryWebhookInboxEntity.InboxStatus.SKIPPED, inboxRepo.findAll().single().status)
    }
}
