package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/**
 * Library > My cases 페이지의 status filter chip 카운트 산정.
 *
 * `list` 와 동일한 필터 (workspaceId / q) 를 적용하되 status 는 무시하고
 * status 별 count 를 반환. 응답에 3 status 모두 포함 (없는 status 는 0).
 *
 * **workspaceId 미지정** = 요청자 본인 케이스만.
 * **workspaceId 지정** = 그 워크스페이스 멤버 확인 후 전체.
 */
@Service
class CountMyCasesByStatusUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val ownerFilter: Long? = if (input.workspaceId != null) {
            if (workspaceQuery.getViewerRole(input.requesterUserId, input.workspaceId)?.canRead() != true) {
                throw ErrorCaseAccessDeniedException("not a member of workspace ${input.workspaceId}")
            }
            null
        } else {
            input.requesterUserId
        }

        val counts = errorCaseRepository.countByStatus(
            ErrorCaseSearchCriteria(
                workspaceId = input.workspaceId,
                ownerUserId = ownerFilter,
                status = null, // status 는 groupBy 대상 — 필터 X
                fingerprint = null,
                visibility = null,
                cursorCreatedAt = null,
                cursorId = null,
                limit = Int.MAX_VALUE,
                q = input.q,
            )
        )

        // 결과에 없는 status 도 0 으로 채워 응답 형태 일정하게.
        val filled = ErrorCaseStatus.entries.associateWith { counts[it] ?: 0L }
        return Result(byStatus = filled, total = filled.values.sum())
    }

    data class Input(
        val requesterUserId: Long,
        val workspaceId: Long?,
        val q: String?,
    )

    data class Result(
        val byStatus: Map<ErrorCaseStatus, Long>,
        val total: Long,
    )
}
