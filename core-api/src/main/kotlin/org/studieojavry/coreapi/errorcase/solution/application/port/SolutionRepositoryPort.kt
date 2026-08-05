package org.studieojavry.coreapi.errorcase.solution.application.port

import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.case.application.port.CaseActivityRow
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import java.time.LocalDateTime

@Repository
interface SolutionRepositoryPort {
    fun save(solution: Solution): Solution
    fun findById(id: Long): Solution?
    fun findAllByErrorCaseId(errorCaseId: Long): List<Solution>

    /** step 이 참조되는 solution 들(step 삭제 보호 판정용). */
    fun findReferencingStep(stepId: Long): List<Solution>

    fun delete(id: Long)
    fun deleteAllByErrorCaseId(errorCaseId: Long)

    // ── Recent Activity (home dashboard) ──────────────────────
    /** 내가 등록한 최근 solution, since 이후. createdAt DESC, id DESC. */
    fun findRecentByAuthor(authorUserId: Long, since: LocalDateTime, limit: Int): List<Solution>

    /** case-id 별 마지막 solution createdAt. */
    fun findMaxCreatedAtByCaseIds(caseIds: Collection<Long>): Map<Long, LocalDateTime>

    /** case-id 별 solution 수 (일괄). */
    fun countByCaseIds(caseIds: Collection<Long>): Map<Long, Long>

    /** 특정 case 에서 since 이후 + author != excludeUserId solution 수. unread 산정용. */
    fun countSinceExcludingAuthor(errorCaseId: Long, since: LocalDateTime, excludeUserId: Long): Long

    /** unread 정렬용 batch — globalSince 이후 활동 fetch. */
    fun findActivitiesByCaseIdsSince(caseIds: Collection<Long>, globalSince: LocalDateTime): List<CaseActivityRow>

    /** since 이후 + author = authorUserId solution 수. activity summary 산정용. */
    fun countByAuthorSince(authorUserId: Long, since: LocalDateTime): Long
}