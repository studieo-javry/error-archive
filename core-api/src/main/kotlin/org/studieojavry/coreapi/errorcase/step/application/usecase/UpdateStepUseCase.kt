package org.studieojavry.coreapi.errorcase.step.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.step.application.command.UpdateStepCommand
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step

/**
 * Step 내용 수정 — **작성자(author) 본인만**. null=유지. attemptType 비우기는 clearAttemptType=true.
 * 케이스 status 영향 없음(SUCCESS 로 변경해도 자동 RESOLVED 전환 안 함 — 사용자가 명시적으로).
 */
@Service
class UpdateStepUseCase(
    private val stepRepository: StepRepositoryPort,
) {
    @Transactional
    fun invoke(command: UpdateStepCommand): Step {
        val step = stepRepository.findById(command.stepId)
            ?: throw StepNotFoundException(command.stepId)
        if (step.authorUserId != command.requesterUserId) {
            throw StepAccessDeniedException("only the author can edit step ${command.stepId}")
        }
        step.update(
            title = command.title,
            status = command.status,
            attemptType = command.attemptType,
            clearAttemptType = command.clearAttemptType,
            body = command.body,
            insight = command.insight,
        )
        return stepRepository.save(step)
    }
}

class StepNotFoundException(val stepId: Long) : RuntimeException("step not found: $stepId")
class StepAccessDeniedException(message: String) : RuntimeException(message)
class StepDeleteConflictException(message: String) : RuntimeException(message)