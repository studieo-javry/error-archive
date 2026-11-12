package org.studieojavry.coreapi.errorcase.solution.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.solution.domain.model.SolutionVerification
import org.studieojavry.coreapi.shared.presentation.toApiOffset
import java.time.OffsetDateTime

data class SolutionResponse(
    val id: Long,
    val errorCaseId: Long,
    val authorUserId: Long,
    val title: String,
    /** 상세 설명(마크다운). agentcapture 반영분만 값이 있을 수 있음. */
    val description: String?,
    /** 검증 근거. agentcapture 반영분만 값이 있을 수 있음. */
    val verification: SolutionVerification?,
    val stepIds: List<Long>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(s: Solution) = SolutionResponse(
            id = s.id!!, errorCaseId = s.errorCaseId, authorUserId = s.authorUserId,
            title = s.title, description = s.description, verification = s.verification,
            stepIds = s.stepIds.toList(),
            createdAt = s.createdAt.toApiOffset(), updatedAt = s.updatedAt.toApiOffset(),
        )
    }
}
