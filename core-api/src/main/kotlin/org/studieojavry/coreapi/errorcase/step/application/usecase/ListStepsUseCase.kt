package org.studieojavry.coreapi.errorcase.step.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step


@Service
class ListStepsUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional(readOnly = true)
    fun invoke(errorCaseId: Long, requesterUserId: Long): List<Step> {
        val errorCase = errorCaseRepository.findById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        access.requireRead(errorCase, requesterUserId)
        return stepRepository.findAllByErrorCaseId(errorCaseId)
    }
}