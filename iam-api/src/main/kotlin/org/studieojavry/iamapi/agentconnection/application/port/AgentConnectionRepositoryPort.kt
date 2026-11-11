package org.studieojavry.iamapi.agentconnection.application.port

import org.studieojavry.iamapi.agentconnection.domain.model.AgentConnection
import java.time.Instant
import java.util.UUID

interface AgentConnectionRepositoryPort {
    fun save(connection: AgentConnection): AgentConnection

    /** introspection 경로 — 제시된 토큰의 sha256 으로 조회. 폐기/만료 판정은 도메인이 한다. */
    fun findByTokenHash(tokenHash: String): AgentConnection?

    /** 관리 화면 목록 — 폐기되지 않은 연결만, 최신순. (만료 여부는 표시용으로 use case 가 판단.) */
    fun listNotRevokedByOwner(ownerUserId: Long): List<AgentConnection>

    /**
     * 본인 소유 연결만 폐기(멱등). 다른 user 또는 unknown connectionId 면 0 반환(가드).
     * 반환: 폐기된 row 수.
     */
    fun revokeByConnectionIdIfOwner(
        connectionId: UUID,
        ownerUserId: Long,
        now: Instant = Instant.now(),
    ): Int

    /** introspection 성공 시 마지막 사용 시각 갱신("last active" 표시용). */
    fun touchLastUsed(connectionId: UUID, at: Instant)
}
