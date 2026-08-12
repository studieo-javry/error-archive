package org.studieojavry.coreapi.errorcase.solution.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution


@Service
class ListSolutionsUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional(readOnly = true)
    fun invoke(errorCaseId: Long, requesterUserId: Long): List<Solution> {
        val errorCase = errorCaseRepository.findById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        access.requireRead(errorCase, requesterUserId)
        return solutionRepository.findAllByErrorCaseId(errorCaseId)
    }
}
