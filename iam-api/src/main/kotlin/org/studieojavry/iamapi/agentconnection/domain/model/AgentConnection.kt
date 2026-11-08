package org.studieojavry.iamapi.agentconnection.domain.model

import org.studieojavry.iamapi.agentconnection.domain.model.vo.AgentScope
import java.time.Instant
import java.util.UUID

/**
 * agent 연결 토큰 1건. 로컬 커넥터(Claude Code MCP)가 agent 전용 경로를 호출할 때 제시하는
 * opaque 토큰의 서버측 기록이다. refresh token 과 같은 해시-저장 패턴을 따른다.
 *
 *  - [connectionId] 는 외부에 노출되는 안정적 식별자(UUID). 멱등 키 네임스페이스·`cid` 클레임에 쓰인다.
 *    토큰 원문을 교체(재발급)해도 같은 연결이면 유지하는 개념(refresh token 의 familyId 대응).
 *  - 토큰 원문은 저장하지 않는다. [tokenHash](sha256) 만 저장하고 조회는 해시로 한다.
 *  - 폐기는 삭제가 아니라 [revokedAt] 스탬프 — 감사 보존. 폐기/만료는 매 요청 introspection 에서 검사.
 */
class AgentConnection private constructor(
    val id: Long?,
    val ownerUserId: Long,
    val connectionId: UUID,
    val tokenHash: String,
    val scopes: Set<AgentScope>,
    val label: String?,
    val expiresAt: Instant,
    val createdAt: Instant,
    var revokedAt: Instant?,
    var lastUsedAt: Instant?,
) {
    init {
        require(tokenHash.isNotBlank()) { "tokenHash must not be blank" }
        require(scopes.isNotEmpty()) { "agent connection must grant at least one scope" }
    }

    val isRevoked: Boolean get() = revokedAt != null

    fun isExpired(now: Instant = Instant.now()): Boolean = !now.isBefore(expiresAt)

    /** introspection 이 매 요청 호출. 폐기/만료 어느 쪽이든 사용 불가. */
    fun isUsable(now: Instant = Instant.now()): Boolean = !isRevoked && !isExpired(now)

    fun hasScope(scope: AgentScope): Boolean = scope in scopes

    fun revoke(now: Instant = Instant.now()) {
        if (revokedAt != null) return
        this.revokedAt = now
    }

    fun touchLastUsed(at: Instant = Instant.now()) {
        this.lastUsedAt = at
    }

    companion object {
        fun issue(
            ownerUserId: Long,
            connectionId: UUID,
            tokenHash: String,
            scopes: Set<AgentScope>,
            label: String?,
            expiresAt: Instant,
            now: Instant = Instant.now(),
        ): AgentConnection = AgentConnection(
            id = null,
            ownerUserId = ownerUserId,
            connectionId = connectionId,
            tokenHash = tokenHash,
            scopes = scopes,
            label = label?.takeIf { it.isNotBlank() },
            expiresAt = expiresAt,
            createdAt = now,
            revokedAt = null,
            lastUsedAt = null,
        )

        fun rehydrate(
            id: Long,
            ownerUserId: Long,
            connectionId: UUID,
            tokenHash: String,
            scopes: Set<AgentScope>,
            label: String?,
            expiresAt: Instant,
            createdAt: Instant,
            revokedAt: Instant?,
            lastUsedAt: Instant?,
        ): AgentConnection = AgentConnection(
            id, ownerUserId, connectionId, tokenHash, scopes, label,
            expiresAt, createdAt, revokedAt, lastUsedAt,
        )
    }
}
