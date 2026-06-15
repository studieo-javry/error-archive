package org.studieojavry.coreapi.errorcase.solution.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.solution.application.command.CreateSolutionCommand
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import java.time.Instant


/**
 * Solution 생성 — 케이스 쓰기 권한. stepIds 는 모두 같은 케이스의 step 이어야 함(다른 케이스 step 거부).
 * 사용자 선택 순서가 stepIds 에 보존된다(@OrderColumn).
 */
@Service
class CreateSolutionUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val access: ErrorCaseAccess,
    private val activityEventPublisher: ActivityEventPublisherPort,
    private val caseWatchlistRepository:
        org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort,
) {
    @Transactional
    fun invoke(command: CreateSolutionCommand): Solution {
        val errorCase = errorCaseRepository.findById(command.errorCaseId)
            ?: throw ErrorCaseNotFoundException(command.errorCaseId)
        access.requireWrite(errorCase, command.authorUserId)

        val unique = command.stepIds.distinct()
        require(unique.size == command.stepIds.size) { "solution stepIds must be unique" }
        if (unique.isEmpty()) {
            throw SolutionInvalidException("solution must reference at least one step")
        }

        val caseStepIds = stepRepository.findAllByErrorCaseId(command.errorCaseId).map { it.id!! }.toSet()
        val foreign = unique.filterNot { it in caseStepIds }
        if (foreign.isNotEmpty()) {
            throw SolutionInvalidException("stepIds $foreign do not belong to error case ${command.errorCaseId}")
        }

        val solution = Solution.create(
            errorCaseId = command.errorCaseId,
            authorUserId = command.authorUserId,
            title = command.title,
            stepIds = command.stepIds,
        )
        val saved = solutionRepository.save(solution)

        // 잔디용 activity event 발행 (fire-and-forget)
        activityEventPublisher.publish(
            ActivityEventPublisherPort.ActivityEvent(
                userId = command.authorUserId,
                type = ActivityEventPublisherPort.Type.SOLUTION_ADDED,
                occurredAt = Instant.now(),
                idempotencyKey = "solution:${saved.id}",
                meta = mapOf("errorCaseId" to command.errorCaseId),
            )
        )

        // 자동 watchlist (멱등) — solution 등록자를 향후 활동 알림 수신자로
        runCatching { caseWatchlistRepository.add(command.errorCaseId, command.authorUserId) }

        return saved
    }
}

/** solution 입력 검증 실패 — stepIds 비어있음·다른 케이스 step·중복 등. → 400. */
class SolutionInvalidException(message: String) : RuntimeException(message)

/** solution 조회 실패. → 404. */
class SolutionNotFoundException(val solutionId: Long) : RuntimeException("solution not found: $solutionId")

/** solution 작성자/케이스 소유자 아님. → 403. */
class SolutionAccessDeniedException(message: String) : RuntimeException(message)
