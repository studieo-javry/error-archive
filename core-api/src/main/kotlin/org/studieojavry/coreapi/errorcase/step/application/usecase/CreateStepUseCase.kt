package org.studieojavry.coreapi.errorcase.step.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.step.application.command.CreateStepCommand
import org.studieojavry.coreapi.errorcase.step.application.port.StepAttemptTypeCatalogPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.AttemptType
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus


/**
 * 에러케이스에 step 추가.
 *
 * 자동 상태 전이:
 *  - 케이스가 `OPEN` 이고 첫 step 이면 → `IN_PROGRESS` 로 전환(같은 트랜잭션).
 *  - 추가된 step.status == RESOLVED 이고 케이스가 IN_PROGRESS 면 → 응답에 `suggestResolve=true` hint.
 *    (실제 RESOLVED 전환은 사용자가 확인 후 PATCH 로 별도 호출.)
 *
 * 자동 카탈로그 등록:
 *  - `attemptType` 값이 system(`AttemptType.SYSTEM`) 도 본인 custom 도 아니면, 본인 커스텀 카탈로그에 멱등 add.
 *    → 같은 사용자의 다음 step 작성 시 자동 완성 후보로 노출.
 */
@Service
class CreateStepUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val attemptTypeCatalog: StepAttemptTypeCatalogPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional
    fun invoke(command: CreateStepCommand): Result {
        val errorCase = errorCaseRepository.findById(command.errorCaseId)
            ?: throw ErrorCaseNotFoundException(command.errorCaseId)
        access.requireWrite(errorCase, command.authorUserId)

        val normalizedAttemptType = command.attemptType?.let { AttemptType.normalize(it) }

        val existingCount = stepRepository.countByErrorCaseId(command.errorCaseId).toInt()
        val step = Step.create(
            errorCaseId = command.errorCaseId,
            authorUserId = command.authorUserId,
            orderIndex = existingCount,
            title = command.title,
            status = command.status,
            attemptType = normalizedAttemptType,
            body = command.body,
            insight = command.insight,
        )
        val saved = stepRepository.save(step)

        // OPEN + 첫 step → IN_PROGRESS 자동 전환
        if (errorCase.status == ErrorCaseStatus.OPEN && existingCount == 0) {
            errorCase.transitionTo(ErrorCaseStatus.IN_PROGRESS)
            errorCaseRepository.update(errorCase)
        }

        normalizedAttemptType?.let { registerCustomIfNew(command.authorUserId, it) }

        // IN_PROGRESS 에서 RESOLVED step → 케이스 RESOLVED 추천(전환은 사용자 확인)
        val suggestResolve = command.status == StepStatus.RESOLVED && errorCase.status == ErrorCaseStatus.IN_PROGRESS

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
