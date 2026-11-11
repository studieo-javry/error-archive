package org.studieojavry.iamapi.agentconnection.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import org.studieojavry.iamapi.agentconnection.application.usecase.IntrospectAgentConnectionUseCase
import java.util.UUID

@Schema(description = "게이트웨이 전용 — 제시된 agent 토큰을 검증(매 요청).")
data class IntrospectAgentTokenRequest(
    @field:NotBlank
    val token: String,
)

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
