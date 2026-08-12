package org.studieojavry.coreapi.errorcase.solution.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.shared.config.DashboardCacheInvalidator


/** Solution 삭제 — 작성자 본인 또는 케이스 소유자. */
@Service
class DeleteSolutionUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val cacheInvalidator: DashboardCacheInvalidator,
) {
    @Transactional
    fun invoke(solutionId: Long, requesterUserId: Long) {
        val solution = solutionRepository.findById(solutionId)
            ?: throw SolutionNotFoundException(solutionId)
        val ownerUserId = errorCaseRepository.findOwnerUserIdById(solution.errorCaseId)
        val allowed = solution.authorUserId == requesterUserId || ownerUserId == requesterUserId
        if (!allowed) throw SolutionAccessDeniedException("not allowed to delete solution $solutionId")
        solutionRepository.delete(solutionId)

        // 캐시 무효화 — case owner 의 recent-active + 작성자의 my-recent-activities (Create 와 대칭).
        runCatching { ownerUserId?.let { cacheInvalidator.evictRecentActive(it) } }
        runCatching { cacheInvalidator.evictMyRecentActivities(solution.authorUserId) }
    }
}
