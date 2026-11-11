package org.studieojavry.iamapi.agentconnection.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import org.studieojavry.iamapi.agentconnection.application.usecase.IntrospectAgentConnectionUseCase
import java.util.UUID

@Schema(description = "게이트웨이 전용 — 제시된 agent 토큰을 검증(매 요청). 설치 메타는 선택(표시용, 인가 불사용).")
data class IntrospectAgentTokenRequest(
    @field:NotBlank
    val token: String,
    @Schema(description = "커넥터 종류(예: claude-code). 커넥터가 신고한 표시용 값.")
    val clientKind: String? = null,
    val clientVersion: String? = null,
    @Schema(description = "설치당 1회 생성된 랜덤 식별자(하드웨어 지문 아님). 표시·그룹용.")
    val installId: String? = null,
    @Schema(description = "사람이 읽는 설치 이름(기본 hostname).")
    val installLabel: String? = null,
) {
    fun toClientDescriptor(): IntrospectAgentConnectionUseCase.ClientDescriptor? {
        if (clientKind == null && clientVersion == null && installId == null && installLabel == null) return null
        return IntrospectAgentConnectionUseCase.ClientDescriptor(clientKind, clientVersion, installId, installLabel)
    }
}

@Schema(description = "검증된 principal. 게이트웨이가 이 값으로 내부 JWT(sub/cid/scp)를 발급한다.")
data class IntrospectAgentTokenResponse(
    val userId: Long,
    val connectionId: UUID,
    val scopes: List<String>,
) {
    companion object {
        fun from(p: IntrospectAgentConnectionUseCase.Principal) = IntrospectAgentTokenResponse(
            userId = p.userId,
            connectionId = p.connectionId,
            scopes = p.scopes.sortedBy { it.wire }.map { it.wire },
        )
    }
}
