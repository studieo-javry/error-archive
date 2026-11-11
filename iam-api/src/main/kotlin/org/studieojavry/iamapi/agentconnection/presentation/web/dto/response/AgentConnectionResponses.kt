package org.studieojavry.iamapi.agentconnection.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import org.studieojavry.iamapi.agentconnection.application.usecase.CreateAgentConnectionUseCase
import org.studieojavry.iamapi.agentconnection.application.usecase.ListAgentConnectionsUseCase
import org.studieojavry.iamapi.agentconnection.domain.model.vo.AgentScope
import java.time.Instant
import java.util.UUID

@Schema(description = "발급 결과. `token` 은 이 응답에서만 볼 수 있다(서버는 해시만 저장).")
data class CreateAgentConnectionResponse(
    val connectionId: UUID,
    @Schema(description = "원문 토큰 — 커넥터가 OS 자격증명 저장소에 보관. 다시 조회 불가.", example = "eaa_xxxxxxxx")
    val token: String,
    val scopes: List<String>,
    val label: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    companion object {
        fun from(r: CreateAgentConnectionUseCase.Result) = CreateAgentConnectionResponse(
            connectionId = r.connection.connectionId,
            token = r.rawToken,
            scopes = r.connection.scopes.sortedBy { it.wire }.map { it.wire },
            label = r.connection.label,
            createdAt = r.connection.createdAt,
            expiresAt = r.connection.expiresAt,
        )
    }
}

@Schema(description = "연결 목록 항목. 토큰 원문은 포함하지 않는다.")
data class AgentConnectionResponse(
    val connectionId: UUID,
    val label: String?,
    val scopes: List<String>,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant,
    @Schema(description = "만료 여부(폐기되진 않았으나 수명이 지난 연결).")
    val expired: Boolean,
) {
    companion object {
        fun from(it: ListAgentConnectionsUseCase.Item) = AgentConnectionResponse(
            connectionId = it.connectionId,
            label = it.label,
            scopes = it.scopes.sortedBy(AgentScope::wire).map { s -> s.wire },
            createdAt = it.createdAt,
            lastUsedAt = it.lastUsedAt,
            expiresAt = it.expiresAt,
            expired = it.expired,
        )
    }
}
