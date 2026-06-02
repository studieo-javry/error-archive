package org.studieojavry.coreapi.errorcase.solution.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import java.time.LocalDateTime

data class SolutionResponse(
    val id: Long,
    val errorCaseId: Long,
    val authorUserId: Long,
    val title: String,
    val stepIds: List<Long>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(s: Solution) = SolutionResponse(
            id = s.id!!, errorCaseId = s.errorCaseId, authorUserId = s.authorUserId,
            title = s.title, stepIds = s.stepIds.toList(),
            createdAt = s.createdAt, updatedAt = s.updatedAt,
        )
    }
}
