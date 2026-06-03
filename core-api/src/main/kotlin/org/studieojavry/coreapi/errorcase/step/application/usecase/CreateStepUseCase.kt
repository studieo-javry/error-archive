package org.studieojavry.coreapi.errorcase.step.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.step.application.command.CreateStepCommand
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus


/**
 * 에러케이스에 step 추가.
 *
 * 자동 상태 전이:
 *  - 케이스가 `OPEN` 이고 첫 step 이면 → `IN_PROGRESS` 로 전환(같은 트랜잭션).
 *  - 추가된 step.status == SUCCESS 이고 케이스가 IN_PROGRESS 면 → 응답에 `suggestResolve=true` hint.
 *    (실제 RESOLVED 전환은 사용자가 확인 후 PATCH 로 별도 호출.)
 */
@Service
class CreateStepUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional
    fun invoke(command: CreateStepCommand): Result {
        val errorCase = errorCaseRepository.findById(command.errorCaseId)
            ?: throw ErrorCaseNotFoundException(command.errorCaseId)
        access.requireWrite(errorCase, command.authorUserId)

        val existingCount = stepRepository.countByErrorCaseId(command.errorCaseId).toInt()
        val step = Step.create(
            errorCaseId = command.errorCaseId,
            authorUserId = command.authorUserId,
            orderIndex = existingCount,
            title = command.title,
            status = command.status,
            attemptType = command.attemptType,
            body = command.body,
            insight = command.insight,
        )
        val saved = stepRepository.save(step)

        // OPEN + 첫 step → IN_PROGRESS 자동 전환
        if (errorCase.status == ErrorCaseStatus.OPEN && existingCount == 0) {
            errorCase.transitionTo(ErrorCaseStatus.IN_PROGRESS)
            errorCaseRepository.update(errorCase)
        }

        // IN_PROGRESS 에서 SUCCESS step → RESOLVED 추천(전환은 사용자 확인)
        val suggestResolve = command.status == StepStatus.SUCCESS && errorCase.status == ErrorCaseStatus.IN_PROGRESS

        return Result(step = saved, caseStatus = errorCase.status, suggestResolve = suggestResolve)
    }

    data class Result(val step: Step, val caseStatus: ErrorCaseStatus, val suggestResolve: Boolean)
}