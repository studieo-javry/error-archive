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
 * 에이전트 case:read — 로컬 커넥터가 과거 해결경험을 **재활용**하기 위해 조회한다.
 * ProjectBinding 과 독립: 본인 소유 Case 라면(바인딩 유무 무관) query 로 검색·상세 조회 가능.
 * projectBindingId 는 특정 프로젝트로 좁히는 **선택 필터**. 공개 사례·타인 데이터는 조회하지 않는다.
 * 사용자 직접 열람으로 집계하지 않는다(조회수·읽음 부수효과 없음).
 */
@Tag(name = "agent-case", description = "에이전트 토큰으로 본인 과거 사례 검색·상세 조회(case:read).")
@RestController
@RequestMapping("/api/v1/agent/cases")
class AgentCaseController(
    private val searchCases: SearchAgentCasesUseCase,
    private val getCaseDetail: GetAgentCaseDetailUseCase,
) {

    @Operation(
        summary = "사례 검색",
        description = "본인 소유 Case 를 query(제목·설명·예외클래스·예외메시지)로 검색. projectBindingId 지정 시 그 프로젝트로 좁힘(선택).",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"))
    @GetMapping
    fun search(
        principal: AgentPrincipal,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) projectBindingId: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): AgentCaseListResponse {
        principal.requireScope(AgentScopes.CASE_READ)
        return AgentCaseListResponse(
            items = searchCases.invoke(principal.userId, query, projectBindingId, limit.coerceIn(1, 100))
                .map(AgentCaseSummaryResponse::from),
        )
    }

    @Operation(
        summary = "사례 상세",
        description = "Step 본문·Solution 설명·검증 포함. 본인 소유만(아니면 404).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "404", description = "없음/본인 소유 아님", content = [Content()]),
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
