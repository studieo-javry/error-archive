package org.studieojavry.iamapi.agentconnection.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.iamapi.agentconnection.application.port.ConnectorVersionSource
import org.studieojavry.iamapi.agentconnection.config.AgentConnectorProperties
import java.time.Instant

/**
 * 최신 커넥터 버전 조회 + TTL 캐시. 성공 결과만 캐시하고 실패(null)는 캐시하지 않아 다음 요청이 재시도한다.
 * 설치 명령은 @latest 라 이 값에 의존하지 않는다 — 표시·고정용.
 */
@Component
class GetConnectorLatestVersionUseCase(
    private val source: ConnectorVersionSource,
    private val properties: AgentConnectorProperties,
) {
    @Volatile
    private var cached: Cached? = null

    fun latest(): String? {
        val now = Instant.now()
        cached?.let { if (it.expiresAt.isAfter(now)) return it.value }
        val fetched = source.fetchLatest()
        if (fetched != null) {
            cached = Cached(fetched, now.plusSeconds(properties.versionCacheTtlSeconds))
        }
        return fetched
    }

    private data class Cached(val value: String, val expiresAt: Instant)
}
