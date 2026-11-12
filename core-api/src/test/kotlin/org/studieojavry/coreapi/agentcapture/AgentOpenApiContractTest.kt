package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Kotlin agent 계약 ↔ 커넥터 OpenAPI 스냅샷 드리프트 가드(이슈 #144, 항목 ②).
 *
 * 살아있는 컨텍스트가 내보내는 `/v3/api-docs/agent`(= 커넥터 타입 생성의 소스)를, 레포에 커밋된
 * 커넥터 스냅샷(`tools/error-archive-agent/openapi/agent-openapi.json`)과 **구조적으로** 비교한다.
 * 어긋나면 = Kotlin DTO/엔드포인트/OpenAPI 설정이 바뀌었는데 스냅샷을 재생성·커밋하지 않은 것.
 *
 * 비교는 포맷(들여쓰기/키 순서)에 무관하게 JSON 트리 동등성으로 한다(객체 키 순서 무시, 배열 순서 유지).
 * 실패 시: 백엔드 기동 후 `cd tools/error-archive-agent && npm run openapi:snapshot && npm run openapi:gen`
 * 로 스냅샷·타입을 재생성해 커밋하면 된다.
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
class AgentOpenApiContractTest {

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        /** core-api 모듈(gradle test 작업디렉터리) 기준과 레포 루트 기준 둘 다 시도. */
        private val SNAPSHOT_CANDIDATES = listOf(
            "../tools/error-archive-agent/openapi/agent-openapi.json",
            "tools/error-archive-agent/openapi/agent-openapi.json",
        )
    }

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var mapper: ObjectMapper

    @Test
    fun `agent 그룹 OpenAPI 가 커넥터 스냅샷과 일치한다`() {
        val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).build()
        val liveJson = mvc.perform(get("/v3/api-docs/agent")).andReturn().response.contentAsString
        val live = mapper.readTree(liveJson)

        val snapshotFile = SNAPSHOT_CANDIDATES.map(::File).firstOrNull { it.isFile }
        assertNotNull(snapshotFile, "커넥터 스냅샷 파일을 찾지 못함(작업디렉터리=${File(".").absolutePath}). 후보=$SNAPSHOT_CANDIDATES")
        val snapshot = mapper.readTree(snapshotFile.readText())

        // 드리프트 지점을 좁혀 보여주기 위해 paths/components 를 먼저 개별 비교.
        assertAll(
            { assertEquals(snapshot.get("paths"), live.get("paths"), "agent 경로/오퍼레이션 드리프트 — 스냅샷 재생성 필요") },
            { assertEquals(snapshot.get("components")?.get("schemas"), live.get("components")?.get("schemas"), "스키마 드리프트 — 스냅샷 재생성 필요") },
            { assertEquals(snapshot, live, "agent OpenAPI 계약이 커넥터 스냅샷과 어긋남 — npm run openapi:snapshot && openapi:gen 후 커밋") },
        )
    }
}
