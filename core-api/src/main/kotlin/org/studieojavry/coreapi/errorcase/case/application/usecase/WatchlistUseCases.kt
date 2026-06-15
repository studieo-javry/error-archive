package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess

/**
 * Watchlist 추가 — 사용자가 *case 활동을 지켜보겠다* 는 명시적 follow.
 *
 * 권한: case 읽기 가능 (PUBLIC 또는 워크스페이스 멤버 또는 owner). PRIVATE 비-owner 는 403.
 * 멱등: 이미 있으면 200 + 기존 row 반환.
 */
@Service
class AddCaseToWatchlistUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val watchlistRepository: CaseWatchlistRepositoryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional
    fun invoke(errorCaseId: Long, userId: Long) {
        val errorCase = errorCaseRepository.findById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        access.requireRead(errorCase, userId)
        watchlistRepository.add(errorCaseId, userId)
    }
}

@Service
class RemoveCaseFromWatchlistUseCase(
    private val watchlistRepository: CaseWatchlistRepositoryPort,
) {
    /** 권한 검증 X — 자기 자신의 watchlist 만 제거. 본인 row 만 삭제 query 라 cross-user 영향 없음. */
    @Transactional
    fun invoke(errorCaseId: Long, userId: Long) {
        watchlistRepository.remove(errorCaseId, userId)
    }
}

@Service
class GetMyWatchlistUseCase(
    private val watchlistRepository: CaseWatchlistRepositoryPort,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long, limit: Int = DEFAULT_LIMIT): List<ErrorCaseSummary> {
        val caseIds = watchlistRepository.findCaseIdsByUserId(userId, limit.coerceIn(1, MAX_LIMIT))
        if (caseIds.isEmpty()) return emptyList()
        return errorCaseRepository.findSummariesByIds(caseIds)
    }

    companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 100
    }
}
