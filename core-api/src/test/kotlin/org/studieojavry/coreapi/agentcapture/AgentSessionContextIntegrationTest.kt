package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.studieojavry.coreapi.agentcapture.application.port.ProjectBindingRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.usecase.CreateCaptureCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.CreateCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.RegisterCaptureSessionUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.RegisterSessionCommand
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBinding
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBindingNotFoundException
import org.studieojavry.coreapi.agentcapture.domain.model.StoragePolicy
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * PR-Y 세션→capture 프로젝트 문맥 배선 검증.
 *  - 세션이 projectBindingId(소유검증) / projectKey(findOrCreate) 로 프로젝트를 받는다.
 *  - 그 세션에서 만든 Capture 가 바인딩을 상속한다(명시 지정 시 override).
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
class AgentSessionContextIntegrationTest {

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var registerSession: RegisterCaptureSessionUseCase
    @Autowired private lateinit var createCapture: CreateCaptureUseCase
    @Autowired private lateinit var bindings: ProjectBindingRepositoryPort

    private val userId = 6600L
    private val conn = UUID.randomUUID()

    private fun newBinding(owner: Long, key: String): Long {
        val now = Instant.now()
        return bindings.save(
            ProjectBinding(ownerUserId = owner, projectKey = key, normalizedRepoId = null,
                storagePolicy = StoragePolicy.DEFAULT, createdAt = now, updatedAt = now),
        ).id!!
    }

    private fun registerCmd(sessionUuid: UUID, projectBindingId: Long? = null, projectKey: String? = null) =
        RegisterSessionCommand(
            sessionUuid = sessionUuid, ownerUserId = userId, connectionId = conn,
            clientKind = "claude-code", clientVersion = "1", clientSessionId = "cs-$sessionUuid",
            projectBindingId = projectBindingId, projectKey = projectKey,
        )

    private fun createCmd(sessionUuid: UUID?, projectBindingId: Long? = null) =
        CreateCaptureCommand(
            captureUuid = UUID.randomUUID(), ownerUserId = userId, connectionId = conn,
            title = "t", project = null, content = CaptureContent.EMPTY,
            sessionUuid = sessionUuid, projectBindingId = projectBindingId,
        )

    @Test
    fun `세션 projectBindingId → capture 상속`() {
        val bindingId = newBinding(userId, "checkout")
        val sessionUuid = UUID.randomUUID()
        val session = registerSession.invoke(registerCmd(sessionUuid, projectBindingId = bindingId))
        assertEquals(bindingId, session.projectBindingId)

        val capture = createCapture.invoke(createCmd(sessionUuid))
        assertEquals(bindingId, capture.projectBindingId) // 세션에서 상속
    }

    @Test
    fun `세션 projectKey → 바인딩 findOrCreate 후 상속`() {
        val sessionUuid = UUID.randomUUID()
        val session = registerSession.invoke(registerCmd(sessionUuid, projectKey = "billing-svc"))
        assertNotNull(session.projectBindingId)
        // 같은 key 로 바인딩이 생성됐다
        assertEquals(session.projectBindingId, bindings.findByOwnerAndKey(userId, "billing-svc")?.id)

        val capture = createCapture.invoke(createCmd(sessionUuid))
        assertEquals(session.projectBindingId, capture.projectBindingId)
    }

    @Test
    fun `capture 명시 projectBindingId 는 세션 상속을 override`() {
        val sessionBinding = newBinding(userId, "proj-a")
        val explicitBinding = newBinding(userId, "proj-b")
        val sessionUuid = UUID.randomUUID()
        registerSession.invoke(registerCmd(sessionUuid, projectBindingId = sessionBinding))

        val capture = createCapture.invoke(createCmd(sessionUuid, projectBindingId = explicitBinding))
        assertEquals(explicitBinding, capture.projectBindingId)
    }

    @Test
    fun `타인 소유 projectBindingId 로 세션 등록 → 404`() {
        val othersBinding = newBinding(7777L, "theirs")
        assertThrows<ProjectBindingNotFoundException> {
            registerSession.invoke(registerCmd(UUID.randomUUID(), projectBindingId = othersBinding))
        }
    }
}
