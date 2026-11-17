package org.studieojavry.iamapi.agentconnection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.studieojavry.iamapi.agentconnection.application.port.ConnectorVersionSource
import org.studieojavry.iamapi.agentconnection.application.usecase.GetConnectorLatestVersionUseCase
import org.studieojavry.iamapi.agentconnection.config.AgentConnectorProperties

class GetConnectorLatestVersionUseCaseTest {

    private class FakeSource(var value: String?) : ConnectorVersionSource {
        var calls = 0
        override fun fetchLatest(): String? {
            calls++
            return value
        }
    }

    private fun props(ttl: Long) = AgentConnectorProperties(versionCacheTtlSeconds = ttl)

    @Test
    fun `TTL 안에서는 캐시 — 두 번 호출해도 소스 1회`() {
        val source = FakeSource("1.11.2")
        val useCase = GetConnectorLatestVersionUseCase(source, props(ttl = 300))
        assertEquals("1.11.2", useCase.latest())
        assertEquals("1.11.2", useCase.latest())
        assertEquals(1, source.calls, "TTL 안에서는 재조회하지 않아야 함")
    }

    @Test
    fun `TTL 0 이면 매 요청 재조회`() {
        val source = FakeSource("1.11.2")
        val useCase = GetConnectorLatestVersionUseCase(source, props(ttl = 0))
        useCase.latest()
        useCase.latest()
        assertEquals(2, source.calls, "TTL 0 은 매번 재조회")
    }

    @Test
    fun `실패(null) 는 캐시하지 않아 다음 요청이 재시도`() {
        val source = FakeSource(null)
        val useCase = GetConnectorLatestVersionUseCase(source, props(ttl = 300))
        assertNull(useCase.latest())
        source.value = "1.11.3" // 다음 조회는 복구
        assertEquals("1.11.3", useCase.latest(), "null 은 캐시 안 되므로 재조회로 복구")
        assertEquals(2, source.calls)
    }
}
