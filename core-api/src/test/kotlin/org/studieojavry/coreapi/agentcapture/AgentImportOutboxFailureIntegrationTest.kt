package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSolutionRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptDetail
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptResult
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAttempt
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSolutionCandidate
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import org.studieojavry.coreapi.agentcapture.domain.model.SolutionContent
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort
import org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.OutboxActivityEventPublisherAdapter
import org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.OutboxEventEntity
import org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.OutboxEventJpaRepository
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Outbox INSERT 실패 → import 전체 롤백 불변식 검증(#144 후속).
 *
 * #168 의 기존 테스트는 **포트-레벨에서 던지는** fake publisher 로 롤백을 봤지만, 실제
 * [OutboxActivityEventPublisherAdapter] 는 예외를 삼켜서(과거) 그 경로가 안 생겼다. 여기서는 **실제
 * 어댑터**를 쓰되 **OutboxEventJpaRepository 의 save 가 DB 오류를 던지도록 주입**해, narrowing 후의 정책
 * (직렬화 외 INSERT 실패는 전파 → 도메인 트랜잭션과 함께 롤백)이 실제로 import 를 롤백하는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(AgentImportOutboxFailureIntegrationTest.Config::class)
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
])
class AgentImportOutboxFailureIntegrationTest {

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    /** 실제 outbox 리포를 위임하되 save 만 DB 오류를 던지도록 교체(위임으로 나머지 메서드는 그대로). */
    class FailingOutboxRepo(private val delegate: OutboxEventJpaRepository) :
        OutboxEventJpaRepository by delegate {
        override fun <S : OutboxEventEntity> save(entity: S): S =
            throw DataIntegrityViolationException("simulated outbox INSERT failure")
    }

    /** 근거 승격 경로 미사용이라 no-op 스토리지로 컨텍스트만 충족. */
    class NoopStorage : AttachmentStoragePort {
        override fun put(objectKey: String, bytes: ByteArray, contentType: String) {}
        override fun presignGet(objectKey: String, disposition: AttachmentStoragePort.Disposition, ttl: java.time.Duration) =
            java.net.URI.create("http://test/$objectKey")
        override fun getBytes(objectKey: String): ByteArray = byteArrayOf()
        override fun delete(objectKey: String) {}
    }

    @TestConfiguration
    class Config {
        /** 실제 어댑터 + 실패 리포 — 포트-fake 가 아니라 프로덕션 어댑터의 전파 정책을 검증. */
        @Bean @Primary
        fun failingOutboxPublisher(outbox: OutboxEventJpaRepository, om: ObjectMapper): ActivityEventPublisherPort =
            OutboxActivityEventPublisherAdapter(FailingOutboxRepo(outbox), om)

        @Bean @Primary
        fun noopStorage(): AttachmentStoragePort = NoopStorage()
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var captures: AgentCaptureRepositoryPort
    @Autowired private lateinit var attempts: CaptureAttemptRepositoryPort
    @Autowired private lateinit var solutions: CaptureSolutionRepositoryPort
    private lateinit var mvc: MockMvc

    private val userId = 7701L

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asUser(uid: Long) = authentication(InternalAuthentication(uid, emptyList(), "test-caller"))

    private fun seedSealed(owner: Long): UUID {
        val uuid = UUID.randomUUID()
        val saved = captures.save(
            AgentCapture.start(
                captureUuid = uuid, ownerUserId = owner, originatingConnectionId = UUID.randomUUID(),
                title = "NPE on checkout", project = "checkout-svc",
                content = CaptureContent(symptom = "NPE at CartService.total()", observedFacts = listOf("stacktrace")),
                now = Instant.now(),
            )
        )
        val captureId = saved.id!!
        attempts.save(CaptureAttempt.create(captureId, "a1", 0, AttemptResult.SUCCEEDED, "fix",
            AttemptDetail(title = "Null-guard price", action = "add ?: 0"), Instant.now()))
        solutions.save(CaptureSolutionCandidate.create(captureId, "s1", "Guard null price",
            SolutionContent(clientAttemptIds = listOf("a1")), Instant.now()))
        val c = captures.findByUuid(uuid)!!
        c.complete(Instant.now())
        captures.save(c)
        return uuid
    }

    @Test
    fun `outbox INSERT 실패 시 import 전체가 롤백된다(커밋 후 이벤트 유실 방지)`() {
        val uuid = seedSealed(userId)
        val body = """{"reviewedRevision":1,"selectedAttemptIds":["a1"],"selectedSolutionIds":["s1"]}"""

        // import → AGENT_IMPORT 이벤트 발행 시 outbox save 가 DB 오류 → 전파 → 트랜잭션 롤백 → 5xx.
        mvc.perform(
            post("/api/v1/captures/$uuid/import").with(asUser(userId))
                .header("Idempotency-Key", "k-outbox-fail-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().is5xxServerError)

        // 불변식: 롤백됐으므로 capture 는 여전히 READY_FOR_REVIEW(IMPORTED 아님) + Case 연결 없음.
        val reloaded = captures.findByUuid(uuid)!!
        assertEquals(CaptureStatus.READY_FOR_REVIEW, reloaded.status)
        assertEquals(null, reloaded.importedCaseId)
    }
}
