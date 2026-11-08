package org.studieojavry.coreapi.agentcapture.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * agent 전용 경로의 진입점 겸 진단. PR1 에서는 인증 체인(게이트웨이 introspection → 내부 JWT 의
 * cid/scp → core 인자 조립)이 끝까지 동작하는지 확인하는 whoami 만 제공한다. 초안 수집·검토 반영
 * 엔드포인트는 다음 PR 에서 이 계약 위에 추가한다.
 */
@Tag(name = "agent", description = "로컬 커넥터(MCP) 전용 경로. opaque 연결 토큰으로만 호출(사용자 JWT 아님).")
@RestController
@RequestMapping("/api/v1/agent")
class AgentSessionController {

    @Operation(
        summary = "연결 확인(whoami)",
        description = """
            제시한 agent 토큰이 어떤 사용자·연결·범위로 해석되는지 반환한다. 커넥터의 설정/진단
            (`doctor`)에서 토큰 유효성과 연결 상태를 확인하는 용도. 특정 scope 를 요구하지 않는다
            (유효한 토큰이면 누구나). 폐기/만료 토큰은 게이트웨이에서 이미 401 로 차단된다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "유효 — principal 반환"),
        ApiResponse(responseCode = "401", description = "토큰 없음/무효/폐기/만료", content = [Content()]),
    )
    @GetMapping("/me")
    fun me(principal: AgentPrincipal): AgentIdentityResponse =
        AgentIdentityResponse(
            userId = principal.userId,
            connectionId = principal.connectionId,
            scopes = principal.scopes.sorted(),
        )

    @Schema(description = "agent 토큰이 해석된 principal.")
    data class AgentIdentityResponse(
        val userId: Long,
        val connectionId: String,
        val scopes: List<String>,
    )
}
