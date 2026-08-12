package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess

/**
 * 에러케이스 상세 조회. 권한은 `ErrorCaseAccess.requireRead` 단일 진입점에 위임 —
 * Visibility(PUBLIC/PRIVATE/WORKSPACE) 분기 + owner + 워크스페이스 멤버.
 */
@Service
class GetErrorCaseUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional(readOnly = true)
    fun invoke(errorCaseId: Long, requesterUserId: Long): ErrorCase {
        val errorCase = errorCaseRepository.findById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        access.requireRead(errorCase, requesterUserId)
        return errorCase
    }
}

/** 케이스 조회/수정 접근 거부(소유자/멤버 아님 / 비공개). → 403 */
class ErrorCaseAccessDeniedException(message: String) : RuntimeException(message)
