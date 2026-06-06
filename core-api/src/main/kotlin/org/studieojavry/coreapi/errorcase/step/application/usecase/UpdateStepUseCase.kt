package org.studieojavry.coreapi.errorcase.step.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.step.application.command.UpdateStepCommand
import org.studieojavry.coreapi.errorcase.step.application.port.StepAttemptTypeCatalogPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.AttemptType
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus

/**
 * Step 내용 수정 — **작성자(author) 본인만**. null=유지. attemptType 비우기는 clearAttemptType=true.
 *
 * 자동 추천:
 *  - 수정 결과 step.status == RESOLVED 이고 케이스가 IN_PROGRESS 면 응답에 `suggestResolve=true` hint.
 *  - 실제 케이스 RESOLVED 전환은 사용자 확인 후 PATCH /error-cases/{id} 로 별도 호출 (자동 X).
 *
 * 자동 카탈로그 등록:
 *  - 새 `attemptType` 값이 system 도 본인 custom 도 아니면 본인 커스텀 카탈로그에 멱등 add.
 */
@Service
class UpdateStepUseCase(
    private val stepRepository: StepRepositoryPort,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val attemptTypeCatalog: StepAttemptTypeCatalogPort,
) {
    @Transactional
    fun invoke(command: UpdateStepCommand): Result {
        val step = stepRepository.findById(command.stepId)
            ?: throw StepNotFoundException(command.stepId)
        if (step.authorUserId != command.requesterUserId) {
            throw StepAccessDeniedException("only the author can edit step ${command.stepId}")
        }

        val normalizedAttemptType = command.attemptType?.let { AttemptType.normalize(it) }

        step.update(
            title = command.title,
            status = command.status,
            attemptType = normalizedAttemptType,
            clearAttemptType = command.clearAttemptType,
            body = command.body,
            insight = command.insight,
        )
        val saved = stepRepository.save(step)

        normalizedAttemptType?.let { registerCustomIfNew(command.requesterUserId, it) }

        val errorCase = errorCaseRepository.findById(step.errorCaseId)
            ?: throw ErrorCaseNotFoundException(step.errorCaseId)
        val suggestResolve = saved.status == StepStatus.RESOLVED && errorCase.status == ErrorCaseStatus.IN_PROGRESS

        return Result(step = saved, caseStatus = errorCase.status, suggestResolve = suggestResolve)
    }

    private fun registerCustomIfNew(userId: Long, attemptType: String) {
        val key = AttemptType.normalizedKey(attemptType)
        if (AttemptType.SYSTEM.any { AttemptType.normalizedKey(it) == key }) return
        if (attemptTypeCatalog.exists(userId, key)) return
        attemptTypeCatalog.add(userId, attemptType, key)
    }

    data class Result(val step: Step, val caseStatus: ErrorCaseStatus, val suggestResolve: Boolean)
}

class StepNotFoundException(val stepId: Long) : RuntimeException("step not found: $stepId")
class StepAccessDeniedException(message: String) : RuntimeException(message)
class StepDeleteConflictException(message: String) : RuntimeException(message)
