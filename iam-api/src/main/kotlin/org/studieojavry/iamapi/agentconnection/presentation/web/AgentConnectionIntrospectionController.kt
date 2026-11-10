package org.studieojavry.iamapi.agentconnection.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.agentconnection.application.usecase.IntrospectAgentConnectionUseCase
import org.studieojavry.iamapi.agentconnection.application.usecase.InvalidAgentTokenException
import org.studieojavry.iamapi.agentconnection.presentation.web.dto.IntrospectAgentTokenRequest
import org.studieojavry.iamapi.agentconnection.presentation.web.dto.IntrospectAgentTokenResponse

/**
 * 내부 전용 — 게이트웨이가 매 agent 요청마다 호출한다. internal 경로는 게이트웨이가 외부로
 * 라우팅하지 않으므로 클러스터 내부에서만 도달하고, 접근은 유효한 내부 JWT(aud=iam-api)로 제한된다.
 */
@Tag(name = "agent-connections-internal", description = "게이트웨이 전용 토큰 introspection.")
@RestController
@RequestMapping("/internal/agent-connections")
class AgentConnectionIntrospectionController(
    private val introspect: IntrospectAgentConnectionUseCase,
) {

    @Operation(
        summary = "agent 토큰 검증(내부)",
        description = "제시된 토큰을 해시 조회 + 폐기/만료 검사(매 요청). 유효하면 userId·connectionId·scopes, 아니면 401.",
    )
    @PostMapping("/introspect")
    fun introspect(@Valid @RequestBody request: IntrospectAgentTokenRequest): IntrospectAgentTokenResponse =
        try {
            IntrospectAgentTokenResponse.from(introspect.invoke(request.token, request.toClientDescriptor()))
        } catch (e: InvalidAgentTokenException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, e.message, e)
        }
}
