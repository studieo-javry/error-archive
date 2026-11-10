package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.ProjectBindingRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCaseNotFoundException
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBindingNotFoundException
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step

/**
 * 에이전트 case:read — 허용 범위(본인 소유 + ProjectBinding 귀속)의 기존 사례 검색.
 * 공개 사례·타인 데이터를 일괄 조회하지 않는다(이슈 §4). 사용자 읽음/조회수 부수효과 없음(조회 유스케이스만 재사용).
 */
@Service
class SearchAgentCasesUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val projectBindings: ProjectBindingRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long, projectBindingId: Long?, limit: Int): List<ErrorCaseSummary> {
        projectBindingId?.let { bid ->
            val binding = projectBindings.findById(bid)
            if (binding == null || binding.ownerUserId != userId) throw ProjectBindingNotFoundException(bid)
        }
        return errorCases.findBoundByOwner(userId, projectBindingId, limit)
    }
}

/** 에이전트 case 상세 — 본인 소유 + 바인딩 귀속만(아니면 404). Step·Solution 포함. 조회수 부수효과 없음. */
@Service
class GetAgentCaseDetailUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val steps: StepRepositoryPort,
    private val caseSolutions: SolutionRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(caseId: Long, userId: Long): Detail {
        val case = errorCases.findById(caseId)
        if (case == null || case.ownerUserId != userId || case.projectBindingId == null) {
            throw AgentCaseNotFoundException(caseId)
        }
        return Detail(
            case = case,
            steps = steps.findAllByErrorCaseId(caseId),
            solutions = caseSolutions.findAllByErrorCaseId(caseId),
        )
    }

    data class Detail(val case: ErrorCase, val steps: List<Step>, val solutions: List<Solution>)
}
