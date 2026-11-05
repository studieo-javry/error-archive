package org.studieojavry.coreapi.errorcase.shared.infrastructure.iam

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder
import org.springframework.web.client.RestClient
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import java.net.InetSocketAddress
import java.util.function.Function
import java.util.function.Supplier
import kotlin.test.assertEquals

/**
 * iam-api `GET /api/v1/workspaces` 응답(WorkspaceListItemResponse) 을 실제 모양 그대로 읽는지.
 * 과거엔 `id` 를 기대해 매번 역직렬화가 실패 → fallback 빈 목록(최근 활동·GitHub 설치 목록에서 워크스페이스 누락).
 */
class IamWorkspaceQueryAdapterTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/workspaces") { ex ->
                // iam-api WorkspaceListItemResponse 필드 그대로
                val body = """[{"workspaceId":1,"name":"studieo-javry","slug":"studieo-javry","role":"ADMIN","createdByUserId":101},
                    |{"workspaceId":7,"name":"Demo","slug":"demo","role":"WRITE","createdByUserId":3}]""".trimMargin().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    @Test
    fun `내 워크스페이스 목록 - iam 응답의 workspaceId 를 읽는다`() {
        val client = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build()
        val cb = DirectCircuitBreakerFactory()
        val adapter = IamWorkspaceQueryAdapter(client, cb, IamWorkspaceRoleReader(client, cb))

        assertEquals(
            listOf(
                WorkspaceQueryPort.WorkspaceSummary(workpaceId = 1, name = "studieo-javry"),
                WorkspaceQueryPort.WorkspaceSummary(workpaceId = 7, name = "Demo"),
            ),
            adapter.getAvailableWorkspaces(101),
        )
    }

    /** 감싸기만 하는 circuit breaker — 실패는 fallback 으로 그대로 넘긴다. */
    private class DirectCircuitBreakerFactory : CircuitBreakerFactory<Unit, ConfigBuilder<Unit>>() {
        override fun create(id: String): CircuitBreaker = object : CircuitBreaker {
            override fun <T : Any> run(toRun: Supplier<T>, fallback: Function<Throwable?, T>): T =
                try { toRun.get() } catch (e: Throwable) { fallback.apply(e) }
        }

        override fun configBuilder(id: String): ConfigBuilder<Unit> = ConfigBuilder { }
        override fun configureDefault(defaultConfiguration: Function<String, Unit>) = Unit
    }
}
