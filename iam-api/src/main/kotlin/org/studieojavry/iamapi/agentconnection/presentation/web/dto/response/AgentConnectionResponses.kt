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

@Schema(description = "연결 목록 항목. 토큰 원문은 포함하지 않는다. 설치 메타는 표시용(커넥터 자기신고).")
data class AgentConnectionResponse(
    val connectionId: UUID,
    val label: String?,
    @Schema(description = "목록에 보여줄 이름: label → 설치 라벨(hostname) → \"{kind} · {cid 앞 8자}\".")
    val displayName: String,
    @Schema(description = "커넥터 종류(예: claude-code). 신고 전이면 null.")
    val clientKind: String?,
    val clientVersion: String?,
    @Schema(description = "사람이 읽는 설치 이름(기본 hostname).")
    val installLabel: String?,
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
            displayName = it.displayName,
            clientKind = it.clientKind,
            clientVersion = it.clientVersion,
            installLabel = it.installLabel,
            scopes = it.scopes.sortedBy(AgentScope::wire).map { s -> s.wire },
            createdAt = it.createdAt,
            lastUsedAt = it.lastUsedAt,
            expiresAt = it.expiresAt,
            expired = it.expired,
        )
    }
}
