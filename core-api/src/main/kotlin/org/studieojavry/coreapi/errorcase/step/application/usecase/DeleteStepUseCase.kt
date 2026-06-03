package org.studieojavry.coreapi.errorcase.step.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step


/**
 * Step 삭제 — 작성자 본인만. **참조하는 solution 이 있으면 409 거부**(사용자가 solution 먼저 정리).
 * 단순한 정책: solution 자동 정리/유지 결정을 사용자에게 명시적으로 시키는 게 덜 헷갈림.
 */
@Service
class DeleteStepUseCase(
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
) {
    @Transactional
    fun invoke(stepId: Long, requesterUserId: Long) {
        val step = stepRepository.findById(stepId)
            ?: throw StepNotFoundException(stepId)
        if (step.authorUserId != requesterUserId) {
            throw StepAccessDeniedException("only the author can delete step $stepId")
        }
        val refs = solutionRepository.findReferencingStep(stepId)
        if (refs.isNotEmpty()) {
            throw StepDeleteConflictException(
                "step $stepId is referenced by ${refs.size} solution(s); delete or update those first"
            )
        }
        stepRepository.delete(stepId)
    }
}