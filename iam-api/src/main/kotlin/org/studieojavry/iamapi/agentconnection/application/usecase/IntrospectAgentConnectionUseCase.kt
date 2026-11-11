package org.studieojavry.iamapi.agentconnection.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.agentconnection.application.port.AgentConnectionRepositoryPort
import org.studieojavry.iamapi.agentconnection.domain.model.vo.AgentScope
import org.studieojavry.iamapi.shared.util.HashUtils
import java.time.Instant
import java.util.UUID

/**
 * 게이트웨이가 매 agent 요청마다 호출한다. 제시된 opaque 토큰을 해시로 조회하고 폐기/만료를
 * **그 자리에서** 검사한다(토큰 상태 캐시 없음 — 폐기는 다음 요청부터 즉시 효력).
 *
 * 성공 시 검증된 principal(userId·connectionId·scopes)을 돌려주고 lastUsedAt 을 갱신한다.
 * 유효하지 않으면 [InvalidAgentTokenException] → 게이트웨이가 401 로 매핑한다. 토큰 존재 여부를
 * 응답으로 구별하지 않는다(폐기/만료/미존재 모두 동일한 invalid).
 */
@Service
class IntrospectAgentConnectionUseCase(
    private val connections: AgentConnectionRepositoryPort,
) {
    @Transactional
    fun invoke(rawToken: String): Principal {
        val token = rawToken.trim()
        if (token.isEmpty()) throw InvalidAgentTokenException()
        val connection = connections.findByTokenHash(HashUtils.sha256(token))
            ?: throw InvalidAgentTokenException()
        val now = Instant.now()
        if (!connection.isUsable(now)) throw InvalidAgentTokenException()

        connections.touchLastUsed(connection.connectionId, now)
        return Principal(
            userId = connection.ownerUserId,
            connectionId = connection.connectionId,
            scopes = connection.scopes,
        )
    }

    data class Principal(
        val userId: Long,
        val connectionId: UUID,
        val scopes: Set<AgentScope>,
    )
}

class InvalidAgentTokenException : RuntimeException("agent token is invalid, revoked, or expired")
