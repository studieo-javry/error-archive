package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.agentcapture.application.port.CaptureEventRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.ProjectBindingRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.usecase.CreateProjectBindingCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.CreateProjectBindingUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.EventResultType
import org.studieojavry.coreapi.agentcapture.application.usecase.IngestEventItem
import org.studieojavry.coreapi.agentcapture.application.usecase.IngestEventsUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.RegisterCaptureSessionUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.RegisterSessionCommand
import org.studieojavry.internalauth.InternalAuthentication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #144 P0 — Agent Capture 동시성 검증. 실제 스레드로 레이스를 일으켜 Testcontainers Postgres
 * 에서 서버의 동시성 보장을 확인한다. 비결정적 결과는 "항상 성립하는 불변식"으로 단언하고,
 * 관찰된 상태코드 분포는 로그로 남겨 설계 특성/FINDING 을 드러낸다.
 *
 * 검증 3건:
 *  1. 동시 중복 요청(같은 Idempotency-Key·captureUuid·payload 동시 생성) → 중복 생성 0.
 *  2. 동시 수정(같은 expectedRevision 로 동시 PATCH) → 정확히 1건만 적용(revision +1).
 *  3. complete 와 Attempt 쓰기 경합 → 최종 봉인, 손상 없음.
 *
 * 아키텍처 사실: agent-capture 에는 비관적 락(SELECT FOR UPDATE)이 없고, capture 행의 JPA
 * @Version 낙관락 + UNIQUE 제약만이 동시성 제어다. 경합의 패자(낙관락 실패·유니크 위반)는
 * AgentCaptureExceptionHandler 가 각각 409 REVISION_CONFLICT / 409 CONCURRENT_REQUEST 로 매핑한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
])
class AgentCaptureConcurrencyIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var om: ObjectMapper
    @Autowired private lateinit var ingestEvents: IngestEventsUseCase
    @Autowired private lateinit var eventsPort: CaptureEventRepositoryPort
    @Autowired private lateinit var createProjectBinding: CreateProjectBindingUseCase
    @Autowired private lateinit var registerSession: RegisterCaptureSessionUseCase
    @Autowired private lateinit var bindingsPort: ProjectBindingRepositoryPort
    private lateinit var mvc: MockMvc

    private val userId = 7001L
    private val connectionId = UUID.randomUUID().toString()
    private val writeScopes = listOf("capture:read", "capture:write")

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun agent() = authentication(
        InternalAuthentication(
            userId,
            writeScopes.map { SimpleGrantedAuthority("SCOPE_$it") },
            "gateway",
            connectionId,
            writeScopes,
        )
    )

    private data class Res(val status: Int, val code: String?, val body: String)

    /** 모든 작업을 CountDownLatch 로 동시에 풀어 진짜 레이스를 만든다. 결과는 작업 순서대로 반환. */
    private fun race(tasks: List<() -> Res>): List<Res> {
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            val gate = CountDownLatch(1)
            val futures = tasks.map { t -> pool.submit(Callable { gate.await(); t() }) }
            gate.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun createReq(uuid: UUID, key: String, title: String): Res {
        val res = mvc.perform(
            post("/api/v1/agent/captures").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "captureUuid" to uuid.toString(),
                    "title" to title,
                    "content" to mapOf("symptom" to "boom", "observedFacts" to listOf("x")),
                ))),
        ).andReturn().response
        return Res(res.status, codeOf(res.contentAsString), res.contentAsString)
    }

    private fun patchReq(uuid: UUID, expectedRevision: Int, title: String): Res {
        val res = mvc.perform(
            patch("/api/v1/agent/captures/$uuid").with(agent())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf("expectedRevision" to expectedRevision, "title" to title))),
        ).andReturn().response
        return Res(res.status, codeOf(res.contentAsString), res.contentAsString)
    }

    private fun attemptReq(uuid: UUID, clientId: String, key: String): Res {
        val res = mvc.perform(
            post("/api/v1/agent/captures/$uuid/attempts").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(mapOf(
                    "clientAttemptId" to clientId,
                    "result" to "FAILED",
                    "detail" to mapOf("title" to "try $clientId", "action" to "ran"),
                ))),
        ).andReturn().response
        return Res(res.status, codeOf(res.contentAsString), res.contentAsString)
    }

    private fun completeReq(uuid: UUID, key: String): Res {
        val res = mvc.perform(
            post("/api/v1/agent/captures/$uuid/complete").with(agent())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andReturn().response
        return Res(res.status, codeOf(res.contentAsString), res.contentAsString)
    }

    private fun getCapture(uuid: UUID): Res {
        val res = mvc.perform(get("/api/v1/agent/captures/$uuid").with(agent())).andReturn().response
        return Res(res.status, codeOf(res.contentAsString), res.contentAsString)
    }

    private fun codeOf(body: String): String? =
        try { om.readTree(body).get("code")?.asText() } catch (_: Exception) { null }

    private fun dist(results: List<Res>): Map<String, Int> =
        results.groupingBy { "${it.status}${it.code?.let { c -> "/$c" } ?: ""}" }.eachCount()

    // ---- 1. 동시 중복 요청 → 중복 생성 없음 -------------------------------------

    @Test
    fun `같은 키-uuid-payload 로 동시 생성해도 중복 capture 가 생기지 않는다`() {
        val uuid = UUID.randomUUID()
        val n = 8
        val results = race((1..n).map { { createReq(uuid, "dup-key", "same") } })
        println("[CREATE race] " + dist(results))

        val successes = results.filter { it.status in 200..299 }
        assertTrue(successes.isNotEmpty(), "적어도 하나는 성공해야 한다")
        // 멱등 보장: 모든 성공 응답은 동일 captureUuid(= 중복 생성 없음). 뒤늦게 온 요청은 영수증 재생.
        val uuids = successes.map { om.readTree(it.body)["captureUuid"].asText() }.toSet()
        assertEquals(setOf(uuid.toString()), uuids, "모든 성공은 같은 captureUuid 여야 한다(중복 생성 금지)")
        // 실패가 있다면 전부 409 CONCURRENT_REQUEST 여야 한다(capture_uuid 유니크 위반 → 멱등 경합으로 매핑).
        // 멱등 키로 재전송하면 최초 결과가 재생된다. (수정 전엔 raw DataIntegrityViolation → 500 으로 샜다.)
        val failures = results.filter { it.status !in 200..299 }
        assertTrue(failures.all { it.status == 409 && it.code == "CONCURRENT_REQUEST" },
            "동시 중복 패자는 409 CONCURRENT_REQUEST — 관찰: ${dist(failures)}")

        // 최종적으로 단 하나의 OPEN capture(rev 0)만 존재.
        val got = getCapture(uuid)
        assertEquals(200, got.status)
        assertEquals("OPEN", om.readTree(got.body)["capture"]["status"].asText())
        assertEquals(0, om.readTree(got.body)["capture"]["revision"].asInt())
    }

    // ---- 2. 동시 수정 → 정확히 1건만 적용 --------------------------------------

    @Test
    fun `같은 revision 으로 동시 PATCH 하면 정확히 하나만 적용된다`() {
        val uuid = UUID.randomUUID()
        createReq(uuid, "k-c2", "orig")

        val results = race(listOf(
            { patchReq(uuid, 0, "edit-A") },
            { patchReq(uuid, 0, "edit-B") },
        ))
        println("[PATCH race] " + dist(results))

        val successes = results.filter { it.status in 200..299 }
        // 손실 수정 방지: 두 요청이 같은 revision 을 보면 하나만 적용돼야 한다.
        assertEquals(1, successes.size, "동시 수정 중 정확히 1건만 성공해야 한다")

        // 최종 revision 은 정확히 0→1 (단 한 번만 증가).
        val got = getCapture(uuid)
        assertEquals(1, om.readTree(got.body)["capture"]["revision"].asInt(), "revision 은 1 이어야 한다(한 번만 적용)")

        // 패자는 409 REVISION_CONFLICT 여야 한다 — 직렬화됐으면 stale expectedRevision 검사로,
        // 진짜 동시면 JPA 낙관락(ObjectOptimisticLockingFailureException)을 어드바이스가 매핑해서.
        // (수정 전엔 낙관락 미매핑으로 500 or github 의 CONNECTION_VERSION_CONFLICT 로 샜다.)
        val loser = results.single { it.status !in 200..299 }
        assertEquals(409, loser.status, "동시 수정 패자는 409 — 관찰 status=${loser.status} code=${loser.code}")
        assertEquals("REVISION_CONFLICT", loser.code, "동시 수정 패자 코드는 REVISION_CONFLICT")
    }

    // ---- 3. complete 와 Attempt 쓰기 경합 --------------------------------------

    @Test
    fun `complete 와 attempt 쓰기를 동시에 보내도 손상되지 않고 최종 봉인된다`() {
        val uuid = UUID.randomUUID()
        createReq(uuid, "k-c3", "orig")

        val results = race(listOf(
            { completeReq(uuid, "k-complete") },
            { attemptReq(uuid, "a1", "k-a1") },
        ))
        val complete = results[0]
        val attempt = results[1]
        println("[COMPLETE vs ATTEMPT] complete=${complete.status} attempt=${attempt.status}/${attempt.code}")

        // complete 와 add-attempt 는 이제 capture 행 PESSIMISTIC_WRITE 락으로 직렬화된다.
        // 따라서 완전히 "attempt→seal"(201) 이거나 "seal→attempt"(409 CAPTURE_SEALED) 중 하나로만
        // 귀결되고, "OPEN 을 읽고 봉인 커밋 뒤에 insert"(봉인된 capture 에 자식이 끼어드는) 인터리빙은
        // 더 이상 불가능하다. 500(손상)도 없다.
        assertEquals(200, complete.status, "complete 는 성공해야 한다")
        val detail = om.readTree(getCapture(uuid).body)
        assertEquals("READY_FOR_REVIEW", detail["capture"]["status"].asText())

        val attemptsNode = detail["attempts"]
        val hasA1 = (0 until attemptsNode.size()).any { attemptsNode[it]["clientAttemptId"].asText() == "a1" }
        if (attempt.status == 201) {
            // attempt 가 먼저 락을 잡아 OPEN 상태에서 추가된 뒤 봉인됨 → 봉인된 capture 에 정상 포함.
            assertTrue(hasA1, "201 이면 attempt 가 봉인된 capture 에 포함돼야 한다")
        } else {
            // complete 가 먼저 봉인 → attempt 는 거부되고 아무것도 추가되지 않아야 한다.
            assertEquals(409, attempt.status, "attempt 는 201 또는 409 — 관찰: ${attempt.status}/${attempt.code}")
            assertEquals("CAPTURE_SEALED", attempt.code)
            assertTrue(!hasA1, "409 면 봉인 후 attempt 가 추가되지 않아야 한다")
        }
    }

    /**
     * #180 FINDING 수정 검증 — 같은 (connectionId, clientEventId) 이벤트를 두 배치가 동시에 적재해도
     * ON CONFLICT DO NOTHING 덕분에 **예외/트랜잭션 오염 없이** 정확히 한 행만 남고 DUPLICATE 로 수렴한다.
     * (예전엔 같은 @Transactional 안에서 UNIQUE 위반을 catch → tx 가 rollback-only 로 오염돼 배치가 깨졌다.)
     */
    @Test
    fun `동시 이벤트 적재 경합 - 배치 깨지지 않고 한 행만, DUPLICATE 로 수렴`() {
        val conn = UUID.randomUUID()
        val owner = 7050L
        val item = IngestEventItem(
            clientEventId = "E-race",
            eventType = "PostToolUse",
            localSequence = 1,
            observedAt = java.time.Instant.now(),
            redactedPayloadJson = """{"cmd":"gradle test","exit":1}""",
            sessionId = "s-race",
        )

        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        val futures = (1..2).map {
            pool.submit(Callable { gate.await(); runCatching { ingestEvents.invoke(conn, owner, listOf(item)) } })
        }
        gate.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        // 어느 배치도 예외로 깨지지 않는다(핵심: tx 오염/UnexpectedRollback 없음).
        assertTrue(results.all { it.isSuccess }, "동시 적재 중 배치가 예외로 깨지면 안 됨: $results")
        val outcomes = results.mapNotNull { it.getOrNull() }.flatten()
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it.result == EventResultType.ACCEPTED || it.result == EventResultType.DUPLICATE },
            "REJECTED/크래시 없이 ACCEPTED|DUPLICATE 로만 수렴해야 함: $outcomes")
        assertEquals(1, outcomes.count { it.result == EventResultType.ACCEPTED }, "정확히 한 번만 삽입(ACCEPTED)")
        // DB 에 정확히 한 행(UNIQUE) — 재조회로 존재 확인.
        assertTrue(eventsPort.findByConnectionAndClientId(conn, "E-race") != null, "이벤트가 정확히 한 행 남아야 함")
    }

    @Test
    fun `같은 clientEventId 다른 payload 는 REJECTED(순차 회귀)`() {
        val conn = UUID.randomUUID()
        val owner = 7051L
        fun item(payload: String) = IngestEventItem(
            clientEventId = "E-dup", eventType = "PostToolUse", localSequence = 1,
            observedAt = java.time.Instant.now(), redactedPayloadJson = payload,
        )
        assertEquals(EventResultType.ACCEPTED, ingestEvents.invoke(conn, owner, listOf(item("""{"a":1}"""))).single().result)
        assertEquals(EventResultType.DUPLICATE, ingestEvents.invoke(conn, owner, listOf(item("""{"a":1}"""))).single().result)
        assertEquals(EventResultType.REJECTED, ingestEvents.invoke(conn, owner, listOf(item("""{"a":2}"""))).single().result)
    }

    /**
     * ProjectBinding 생성도 #180 과 같은 동시 생성 경합이 있었다(선조회+save, catch 없음).
     * ON CONFLICT findOrCreate 로 — 웹 동시 생성이 409/예외 없이 한 바인딩으로 수렴한다.
     */
    @Test
    fun `ProjectBinding 웹 동시 생성 - 409 없이 한 바인딩으로 수렴`() {
        val owner = 7060L
        val key = "checkout-svc"
        val cmd = CreateProjectBindingCommand(ownerUserId = owner, projectKey = key)

        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        val futures = (1..2).map { pool.submit(Callable { gate.await(); runCatching { createProjectBinding.invoke(cmd) } }) }
        gate.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(results.all { it.isSuccess }, "동시 생성이 예외(409 CONCURRENT_REQUEST 등)로 깨지면 안 됨: $results")
        val ids = results.mapNotNull { it.getOrNull()?.binding?.id }.toSet()
        assertEquals(1, ids.size, "같은 (owner, projectKey)는 하나의 바인딩으로 수렴")
        assertEquals(1, bindingsPort.listByOwner(owner).count { it.projectKey == key }, "DB 에 바인딩 한 개")
    }

    /**
     * 같은 레포 다중 세션 동시 시작 — resolveBinding 의 바인딩 findOrCreate 경합에도 세션 등록
     * 트랜잭션이 깨지지 않고, 두 세션이 같은 바인딩에 귀속된다(예전엔 UNIQUE 위반으로 세션 등록 실패 → 미귀속).
     */
    @Test
    fun `ProjectBinding 세션 동시 등록 - 경합에도 세션 안 깨지고 같은 바인딩 귀속`() {
        val owner = 7061L
        val conn = UUID.randomUUID()
        val key = "svc-multi"
        fun cmd() = RegisterSessionCommand(
            sessionUuid = UUID.randomUUID(), ownerUserId = owner, connectionId = conn,
            clientKind = "claude-code", clientVersion = null, clientSessionId = UUID.randomUUID().toString(),
            projectKey = key,
        )

        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        val futures = (1..2).map { pool.submit(Callable { gate.await(); runCatching { registerSession.invoke(cmd()) } }) }
        gate.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(results.all { it.isSuccess }, "세션 등록이 바인딩 생성 경합으로 깨지면 안 됨: $results")
        val bids = results.mapNotNull { it.getOrNull()?.projectBindingId }.toSet()
        assertEquals(1, bids.size, "두 세션이 같은 바인딩에 귀속되어야 함")
        assertEquals(1, bindingsPort.listByOwner(owner).count { it.projectKey == key }, "DB 에 바인딩 한 개")
    }
}
