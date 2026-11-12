package org.studieojavry.coreapi.agentcapture.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import org.studieojavry.coreapi.agentcapture.application.usecase.GetAgentCaseDetailUseCase
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import java.time.LocalDateTime

@Schema(description = "에이전트 case 검색 결과 요약(본인 소유).")
data class AgentCaseSummaryResponse(
    val id: Long,
    val title: String,
    val status: String,
    @Schema(description = "예외 클래스(스냅샷) — 어떤 오류인지 빠른 식별용.")
    val exceptionClass: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(s: ErrorCaseSummary) = AgentCaseSummaryResponse(
            id = s.id, title = s.title, status = s.status.name,
            exceptionClass = s.exceptionClass, createdAt = s.createdAt,
        )
    }
}

@Schema(description = "에이전트 case 검색 결과 목록.")
data class AgentCaseListResponse(val items: List<AgentCaseSummaryResponse>)

@Schema(description = "case 상세의 Step(본문 포함 — 해결 과정 재활용용).")
data class AgentCaseStepResponse(
    val orderIndex: Int,
    val title: String,
    val status: String,
    val attemptType: String?,
    @Schema(description = "Step 본문(마크다운) — 무엇을 어떻게 시도/해결했는지.")
    val body: String?,
    val insight: String?,
)

@Schema(description = "Solution 의 검증 근거.")
data class AgentCaseVerificationResponse(
    val result: String?,
    val executed: Boolean,
    val command: String?,
    val testId: String?,
    val ranAt: String?,
    val commitSha: String?,
    val evidence: String?,
)

@Schema(description = "case 상세의 Solution(설명·검증 포함).")
data class AgentCaseSolutionResponse(
    val title: String,
    @Schema(description = "해결방법 상세 설명(마크다운).")
    val description: String?,
    val verification: AgentCaseVerificationResponse?,
)

@Schema(description = "에이전트 case 상세 — 본인 소유만. Step 본문·Solution 설명·검증까지 포함.")
data class AgentCaseDetailResponse(
    val id: Long,
    val title: String,
    val status: String,
    @Schema(description = "자유 텍스트 프로젝트(P0). ProjectBinding 과는 별개.")
    val project: String?,
    val projectBindingId: Long?,
    val description: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?,
    val steps: List<AgentCaseStepResponse>,
    val solutions: List<AgentCaseSolutionResponse>,
) {
    companion object {
        fun from(d: GetAgentCaseDetailUseCase.Detail): AgentCaseDetailResponse {
            val c = d.case
            return AgentCaseDetailResponse(
                id = requireNotNull(c.id),
                title = c.title,
                status = c.status.name,
                project = c.project,
                projectBindingId = c.projectBindingId,
                description = c.description,
                exceptionClass = c.snapshot?.exceptionClass,
                exceptionMessage = c.snapshot?.exceptionMessage,
                steps = d.steps.sortedBy { it.orderIndex }.map(::stepOf),
                solutions = d.solutions.map(::solutionOf),
            )
        }

        private fun stepOf(s: Step) = AgentCaseStepResponse(
            orderIndex = s.orderIndex, title = s.title, status = s.status.name,
            attemptType = s.attemptType, body = s.body, insight = s.insight,
        )

        private fun solutionOf(s: Solution) = AgentCaseSolutionResponse(
            title = s.title,
            description = s.description,
            verification = s.verification?.let {
                AgentCaseVerificationResponse(
                    result = it.result, executed = it.executed, command = it.command,
                    testId = it.testId, ranAt = it.ranAt, commitSha = it.commitSha, evidence = it.evidence,
                )
            },
        )
    }
}
