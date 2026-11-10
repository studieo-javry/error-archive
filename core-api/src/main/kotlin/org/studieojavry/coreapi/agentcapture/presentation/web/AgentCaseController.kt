package org.studieojavry.coreapi.agentcapture.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.agentcapture.application.usecase.GetAgentCaseDetailUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.SearchAgentCasesUseCase
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AgentCaseDetailResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AgentCaseListResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AgentCaseSummaryResponse

/**
 * 에이전트 case:read 경로 — 로컬 커넥터가 과거 사례를 **허용 범위 안에서** 조회(재사용).
 * 스코프: 본인 소유 + ProjectBinding 귀속 케이스만. 공개 사례·타인 데이터 일괄 조회 불가.
 * 사용자 직접 열람으로 집계하지 않는다(조회수·읽음시각 부수효과 없음).
 */
@Tag(name = "agent-case", description = "에이전트 토큰으로 허용 범위의 기존 사례 검색·조회(case:read).")
@RestController
@RequestMapping("/api/v1/agent/cases")
class AgentCaseController(
    private val searchCases: SearchAgentCasesUseCase,
    private val getCaseDetail: GetAgentCaseDetailUseCase,
) {

    @Operation(summary = "사례 검색", description = "본인 소유 + 바인딩 귀속 케이스. projectBindingId 로 특정 바인딩만 필터.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"))
    @GetMapping
    fun search(
        principal: AgentPrincipal,
        @RequestParam(required = false) projectBindingId: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): AgentCaseListResponse {
        principal.requireScope(AgentScopes.CASE_READ)
        return AgentCaseListResponse(
            items = searchCases.invoke(principal.userId, projectBindingId, limit.coerceIn(1, 100))
                .map(AgentCaseSummaryResponse::from),
        )
    }

    @Operation(summary = "사례 상세", description = "Step·Solution 포함. 본인 소유 + 바인딩 귀속만(아니면 404).")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "404", description = "없음/허용 범위 밖", content = [Content()]),
    )
    @GetMapping("/{caseId}")
    fun detail(
        principal: AgentPrincipal,
        @PathVariable caseId: Long,
    ): AgentCaseDetailResponse {
        principal.requireScope(AgentScopes.CASE_READ)
        return AgentCaseDetailResponse.from(getCaseDetail.invoke(caseId, principal.userId))
    }
}
