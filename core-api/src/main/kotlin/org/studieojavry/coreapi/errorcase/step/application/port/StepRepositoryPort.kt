package org.studieojavry.coreapi.errorcase.step.application.port

import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.step.domain.model.Step

@Repository
interface StepRepositoryPort {
    fun save(step: Step): Step
    fun findById(id: Long): Step?

    /** 케이스의 step 전체. `orderIndex ASC, id ASC`. */
    fun findAllByErrorCaseId(errorCaseId: Long): List<Step>

    /** 케이스의 step 갯수(자동 `IN_PROGRESS` 전환 판정·`orderIndex` 산출). */
    fun countByErrorCaseId(errorCaseId: Long): Long

    /** 케이스에 첫 SUCCESS step 이 있는지(RESOLVED 추천 트리거 판정). */
    fun existsSuccessByErrorCaseId(errorCaseId: Long): Boolean

    fun delete(id: Long)
    fun deleteAllByErrorCaseId(errorCaseId: Long)
}
