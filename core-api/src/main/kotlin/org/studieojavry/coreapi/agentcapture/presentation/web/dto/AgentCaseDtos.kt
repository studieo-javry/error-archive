package org.studieojavry.coreapi.agentcapture.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import org.studieojavry.coreapi.agentcapture.application.usecase.GetAgentCaseDetailUseCase
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import java.time.LocalDateTime

@Schema(description = "에이전트 case 검색 결과 요약(허용 범위: 본인 소유 + 바인딩 귀속).")
data class AgentCaseSummaryResponse(
    val id: Long,
    val title: String,
    val status: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(s: ErrorCaseSummary) = AgentCaseSummaryResponse(
            id = s.id,
            title = s.title,
            status = s.status.name,
            createdAt = s.createdAt,
        )
    }
}

@Schema(description = "에이전트 case 검색 결과 목록.")
data class AgentCaseListResponse(
    val items: List<AgentCaseSummaryResponse>,
)

@Schema(description = "case 상세의 Step 요약.")
data class AgentCaseStepResponse(val title: String, val status: String)

@Schema(description = "case 상세의 Solution 요약.")
data class AgentCaseSolutionResponse(val title: String)

@Schema(description = "에이전트 case 상세(Step·Solution 포함). 본인 소유 + 바인딩 귀속만.")
data class AgentCaseDetailResponse(
    val id: Long,
    val title: String,
    val status: String,
    val project: String?,
    val projectBindingId: Long?,
    val steps: List<AgentCaseStepResponse>,
    val solutions: List<AgentCaseSolutionResponse>,
) {
    companion object {
        fun from(d: GetAgentCaseDetailUseCase.Detail) = AgentCaseDetailResponse(
            id = requireNotNull(d.case.id),
            title = d.case.title,
            status = d.case.status.name,
            project = d.case.project,
            projectBindingId = d.case.projectBindingId,
            steps = d.steps.sortedBy { it.orderIndex }.map { AgentCaseStepResponse(it.title, it.status.name) },
            solutions = d.solutions.map { AgentCaseSolutionResponse(it.title) },
        )
    }
}
