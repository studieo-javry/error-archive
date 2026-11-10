package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.studieojavry.coreapi.agentcapture.application.port.ProjectBindingRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBinding
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/**
 * P1 토대 — ProjectBinding 영속(V23). Testcontainers + ddl-validate(V23↔엔티티 정합 증명).
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
class ProjectBindingRepositoryIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var bindings: ProjectBindingRepositoryPort

    @Test
    fun `저장·소유자키 조회·소유자별 목록`() {
        val now = Instant.parse("2026-11-10T00:00:00Z")
        val saved = bindings.save(
            ProjectBinding(
                ownerUserId = 7001L,
                projectKey = "checkout-svc",
                normalizedRepoId = "github:acme/checkout",
                createdAt = now,
                updatedAt = now,
            ),
        )
        assertNotNull(saved.id)

        val byKey = bindings.findByOwnerAndKey(7001L, "checkout-svc")
        assertEquals(saved.id, byKey?.id)
        assertEquals("github:acme/checkout", byKey?.normalizedRepoId)

        // 다른 소유자의 같은 키는 분리
        bindings.save(ProjectBinding(ownerUserId = 7002L, projectKey = "checkout-svc", createdAt = now, updatedAt = now))
        assertEquals(1, bindings.listByOwner(7001L).size)
        assertEquals(1, bindings.listByOwner(7002L).size)
    }
}
