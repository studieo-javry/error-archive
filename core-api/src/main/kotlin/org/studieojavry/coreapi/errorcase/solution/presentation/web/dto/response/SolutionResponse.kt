package org.studieojavry.coreapi.errorcase.solution.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.shared.presentation.toApiOffset
import java.time.OffsetDateTime

data class SolutionResponse(
    val id: Long,
    val errorCaseId: Long,
    val authorUserId: Long,
    val title: String,
    val stepIds: List<Long>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(s: Solution) = SolutionResponse(
            id = s.id!!, errorCaseId = s.errorCaseId, authorUserId = s.authorUserId,
            title = s.title, stepIds = s.stepIds.toList(),
            createdAt = s.createdAt.toApiOffset(), updatedAt = s.updatedAt.toApiOffset(),
        )
    }
}
