package org.studieojavry.coreapi.errorcase.solution.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution


/** Solution 삭제 — 작성자 본인 또는 케이스 소유자. */
@Service
class DeleteSolutionUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
) {
    @Transactional
    fun invoke(solutionId: Long, requesterUserId: Long) {
        val solution = solutionRepository.findById(solutionId)
            ?: throw SolutionNotFoundException(solutionId)
        if (solution.authorUserId == requesterUserId) {
            solutionRepository.delete(solutionId); return
        }
        val errorCase = errorCaseRepository.findById(solution.errorCaseId)
        if (errorCase != null && errorCase.ownerUserId == requesterUserId) {
            solutionRepository.delete(solutionId); return
        }
        throw SolutionAccessDeniedException("not allowed to delete solution $solutionId")
    }
}
