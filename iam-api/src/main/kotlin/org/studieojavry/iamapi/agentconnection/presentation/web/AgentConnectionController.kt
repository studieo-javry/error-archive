package org.studieojavry.iamapi.agentconnection.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.agentconnection.application.usecase.AgentConnectionNotFoundException
import org.studieojavry.iamapi.agentconnection.application.usecase.CreateAgentConnectionUseCase
import org.studieojavry.iamapi.agentconnection.application.usecase.ListAgentConnectionsUseCase
import org.studieojavry.iamapi.agentconnection.application.usecase.RevokeAgentConnectionUseCase
import org.studieojavry.iamapi.agentconnection.domain.model.vo.AgentScope
import org.studieojavry.iamapi.agentconnection.presentation.web.dto.request.CreateAgentConnectionRequest
import org.studieojavry.iamapi.agentconnection.presentation.web.dto.response.AgentConnectionResponse
import org.studieojavry.iamapi.agentconnection.presentation.web.dto.response.CreateAgentConnectionResponse
import java.util.UUID

@Tag(
    name = "agent-connections",
    description = "로컬 커넥터(Claude Code MCP) 연결 토큰 관리. 웹 로그인 세션에서만 발급/조회/폐기(토큰의 sub=userId 기준).",
)
@RestController
@RequestMapping("/api/v1/me/agent-connections")
class AgentConnectionController(
    private val createAgentConnection: CreateAgentConnectionUseCase,
    private val listAgentConnections: ListAgentConnectionsUseCase,
    private val revokeAgentConnection: RevokeAgentConnectionUseCase,
) {

    @Operation(
        summary = "agent 연결 토큰 발급",
        description = """
            로컬 커넥터가 `/api/v1/agent/**` 를 호출할 때 제시할 opaque 토큰을 발급한다.

            - 응답의 `token` 은 **이 응답에서만** 볼 수 있다(서버는 sha256 해시만 저장). 분실 시 재발급.
            - 기본 만료 30일(자동 연장 없음). scope 는 capture:read/capture:write/artifact:write/case:read 로 제한.
            - 이 토큰은 일반 웹 CRUD·발행·검토 API 를 호출할 수 없다(경로·application 권한 이중 차단).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "발급됨 — token 포함"),
        ApiResponse(responseCode = "400", description = "알 수 없는 scope 값", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateAgentConnectionRequest,
    ): CreateAgentConnectionResponse {
        val userId = currentUserId(jwt)
        val scopes = parseScopes(request.scopes)
        val result = createAgentConnection.invoke(ownerUserId = userId, label = request.label, scopes = scopes)
        return CreateAgentConnectionResponse.from(result)
    }

    @Operation(
        summary = "내 agent 연결 목록",
        description = "폐기되지 않은 연결만. 토큰 원문은 포함하지 않으며, 만료된(미폐기) 연결은 `expired=true` 로 표시.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
    ): List<AgentConnectionResponse> {
        val userId = currentUserId(jwt)
        return listAgentConnections.invoke(userId).map(AgentConnectionResponse::from)
    }

    @Operation(
        summary = "agent 연결 폐기",
        description = """
            지정한 연결을 폐기한다. 폐기는 **다음 요청부터 즉시 효력**(상태 캐시 없음, introspection 이 매 요청 검사).
            기록(이미 수집된 초안·첨부)은 삭제하지 않는다. 본인 소유만, 멱등(모르는/타인 id → 404).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "폐기됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "404", description = "본인 활성 연결 중 해당 id 없음", content = [Content()]),
    )
    @DeleteMapping("/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "연결 식별자(connectionId UUID)") @PathVariable connectionId: UUID,
    ) {
        val userId = currentUserId(jwt)
        try {
            revokeAgentConnection.invoke(userId, connectionId)
        } catch (e: AgentConnectionNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        }
    }

    private fun parseScopes(wire: List<String>?): Set<AgentScope> {
        if (wire.isNullOrEmpty()) return AgentScope.DEFAULTS
        return wire.map { raw ->
            AgentScope.fromWireOrNull(raw)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown scope: $raw")
        }.toSet()
    }

    private fun currentUserId(jwt: Jwt): Long =
        jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid subject claim")
}
