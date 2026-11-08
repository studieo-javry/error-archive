package org.studieojavry.iamapi.agentconnection.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.agentconnection.application.port.AgentConnectionRepositoryPort
import org.studieojavry.iamapi.agentconnection.domain.model.AgentConnection
import org.studieojavry.iamapi.agentconnection.domain.model.vo.AgentScope
import org.studieojavry.iamapi.auth.application.port.SecureRandomPort
import org.studieojavry.iamapi.shared.util.HashUtils
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Settings → Developer → "Agent connections" (로컬 커넥터 연결).
 *
 * 웹 로그인 세션에서만 발급/조회/폐기한다. 토큰 원문은 생성 응답에서 **최초 1회만** 노출하고
 * 서버는 sha256 해시만 저장한다(refresh token 과 동일). 폐기는 삭제가 아닌 revoked_at 스탬프.
 */
@Service
class CreateAgentConnectionUseCase(
    private val connections: AgentConnectionRepositoryPort,
    private val secureRandom: SecureRandomPort,
) {
    @Transactional
    fun invoke(ownerUserId: Long, label: String?, scopes: Set<AgentScope>): Result {
        val granted = scopes.ifEmpty { AgentScope.DEFAULTS }
        val now = Instant.now()
        // opaque 토큰: 식별 가능한 prefix + 256bit 난수. 해시 전 '전체 문자열'을 저장·조회 기준으로 쓴다.
        val rawToken = TOKEN_PREFIX + secureRandom.generateUrlSafeToken(32)
        val connection = connections.save(
            AgentConnection.issue(
                ownerUserId = ownerUserId,
                connectionId = UUID.randomUUID(),
                tokenHash = HashUtils.sha256(rawToken),
                scopes = granted,
                label = label,
                expiresAt = now.plus(TOKEN_TTL),
                now = now,
            )
        )
        return Result(connection = connection, rawToken = rawToken)
    }

    data class Result(val connection: AgentConnection, val rawToken: String)

    companion object {
        const val TOKEN_PREFIX = "eaa_" // error-archive agent
        val TOKEN_TTL: Duration = Duration.ofDays(30)
    }
}

@Service
class ListAgentConnectionsUseCase(
    private val connections: AgentConnectionRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(ownerUserId: Long): List<Item> {
        val now = Instant.now()
        return connections.listNotRevokedByOwner(ownerUserId).map {
            Item(
                connectionId = it.connectionId,
                label = it.label,
                scopes = it.scopes,
                createdAt = it.createdAt,
                lastUsedAt = it.lastUsedAt,
                expiresAt = it.expiresAt,
                expired = it.isExpired(now),
            )
        }
    }

    data class Item(
        val connectionId: UUID,
        val label: String?,
        val scopes: Set<AgentScope>,
        val createdAt: Instant,
        val lastUsedAt: Instant?,
        val expiresAt: Instant,
        val expired: Boolean,
    )
}

@Service
class RevokeAgentConnectionUseCase(
    private val connections: AgentConnectionRepositoryPort,
) {
    /**
     * 본인 소유 연결만 폐기(멱등). 다음 요청부터 introspection 이 거부한다(상태 캐시 없음).
     * 모르는/타인 connectionId → 404(정보 노출 회피).
     */
    @Transactional
    fun invoke(ownerUserId: Long, connectionId: UUID) {
        val affected = connections.revokeByConnectionIdIfOwner(connectionId, ownerUserId)
        if (affected == 0) throw AgentConnectionNotFoundException(connectionId)
    }
}

class AgentConnectionNotFoundException(val connectionId: UUID) :
    RuntimeException("agent connection not found: $connectionId")
