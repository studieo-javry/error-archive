package org.studieojavry.coreapi.errorcase.solution.application.port

import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution

@Repository
interface SolutionRepositoryPort {
    fun save(solution: Solution): Solution
    fun findById(id: Long): Solution?
    fun findAllByErrorCaseId(errorCaseId: Long): List<Solution>

    /** step 이 참조되는 solution 들(step 삭제 보호 판정용). */
    fun findReferencingStep(stepId: Long): List<Solution>

    fun delete(id: Long)
    fun deleteAllByErrorCaseId(errorCaseId: Long)
}