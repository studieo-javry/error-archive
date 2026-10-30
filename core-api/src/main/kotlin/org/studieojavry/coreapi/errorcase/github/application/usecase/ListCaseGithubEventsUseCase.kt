package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess

/** "이전 GitHub 이력 더 보기" — 근거 조회가 준 커서 다음 쪽. 로직은 [GithubActivityReader] 하나를 같이 쓴다. */
@Service
@ConditionalOnGithubEnabled
class ListCaseGithubEventsUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val access: ErrorCaseAccess,
    private val activity: GithubActivityReader,
) {
    /** 한 쪽의 이벤트와 건수를 같은 시점에서 읽도록 한 스냅샷. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun invoke(caseId: Long, viewerUserId: Long, cursor: String?, size: Int): GithubActivityReader.ActivityPage {
        val errorCase = errorCases.findById(caseId) ?: throw ErrorCaseNotFoundException(caseId)
        access.requireRead(errorCase, viewerUserId)
        return activity.page(errorCase, viewerUserId, cursor, size)
    }
}
