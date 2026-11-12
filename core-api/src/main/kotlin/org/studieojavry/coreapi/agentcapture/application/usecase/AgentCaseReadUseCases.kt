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
 * 에이전트 case:read — 과거 해결경험 **조회**. ProjectBinding 과 독립이다(#161 후속 재설계):
 *  - 본인 소유 Case 라면 바인딩 귀속 여부와 무관하게 조회 대상(미귀속 Case 도 포함).
 *  - projectBindingId 는 **선택적 필터**(특정 프로젝트로 좁히기)일 뿐 접근 전제조건이 아니다.
 *  - 권한은 case:read 스코프 + 소유자 일치로 판정(연결별 세분 정책은 P2).
 * 조회수·읽음 등 사용자 열람 부수효과는 발생시키지 않는다.
 */
@Service
class SearchAgentCasesUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val projectBindings: ProjectBindingRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long, query: String?, projectBindingId: Long?, limit: Int): List<ErrorCaseSummary> {
        projectBindingId?.let { bid ->
            val binding = projectBindings.findById(bid)
            if (binding == null || binding.ownerUserId != userId) throw ProjectBindingNotFoundException(bid)
        }
        return errorCases.searchOwnedCasesForAgent(userId, query, projectBindingId, limit)
    }
}

/**
 * 에이전트 case 상세 — 본인 소유만(아니면 404). Step 본문·Solution 설명·검증까지 반환해
 * AI 가 과거 해결과정을 실제로 재활용할 수 있게 한다. 바인딩 귀속은 요건이 아니다.
 */
@Service
class GetAgentCaseDetailUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val steps: StepRepositoryPort,
    private val caseSolutions: SolutionRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(caseId: Long, userId: Long): Detail {
        val case = errorCases.findById(caseId)
        if (case == null || case.ownerUserId != userId) throw AgentCaseNotFoundException(caseId)
        return Detail(
            case = case,
            steps = steps.findAllByErrorCaseId(caseId),
            solutions = caseSolutions.findAllByErrorCaseId(caseId),
        )
    }

    data class Detail(val case: ErrorCase, val steps: List<Step>, val solutions: List<Solution>)
}
