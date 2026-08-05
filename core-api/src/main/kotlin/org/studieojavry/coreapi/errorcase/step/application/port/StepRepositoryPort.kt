package org.studieojavry.coreapi.errorcase.step.application.port

import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.case.application.port.CaseActivityRow
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import java.time.LocalDateTime

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

    // ── Recent Activity (home dashboard) ──────────────────────
    /** 내가 만든 최근 step, since 이후. createdAt DESC, id DESC. */
    fun findRecentByAuthor(authorUserId: Long, since: LocalDateTime, limit: Int): List<Step>

    /** case-id 별 마지막 step createdAt. */
    fun findMaxCreatedAtByCaseIds(caseIds: Collection<Long>): Map<Long, LocalDateTime>

    /** case-id 별 step 수 (일괄). */
    fun countByCaseIds(caseIds: Collection<Long>): Map<Long, Long>

    /** 특정 case 에서 since 이후 + author != excludeUserId step 수. unread 산정용. */
    fun countSinceExcludingAuthor(errorCaseId: Long, since: LocalDateTime, excludeUserId: Long): Long

    /** unread 정렬용 batch — globalSince 이후 활동 fetch. */
    fun findActivitiesByCaseIdsSince(caseIds: Collection<Long>, globalSince: LocalDateTime): List<CaseActivityRow>

    /** since 이후 + author = authorUserId step 수. activity summary 산정용. */
    fun countByAuthorSince(authorUserId: Long, since: LocalDateTime): Long
}
