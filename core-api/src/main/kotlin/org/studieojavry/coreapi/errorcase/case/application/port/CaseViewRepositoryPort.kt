package org.studieojavry.coreapi.errorcase.case.application.port

import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.case.domain.model.CaseView
import java.time.LocalDateTime

/**
 * `case_view` 테이블 접근 — PK (userId, errorCaseId). MVP: owner 자기 case 만.
 */
@Repository
interface CaseViewRepositoryPort {

    /** upsert. 이미 row 있으면 lastViewedAt 만 갱신. */
    fun upsert(view: CaseView)

    /** (userId, caseIds) 별 lastViewedAt 일괄 조회. row 없는 case 는 map 에서 누락. */
    fun findByUserAndCaseIds(userId: Long, caseIds: Collection<Long>): Map<Long, LocalDateTime>

    /** 케이스 삭제 시 cascade 정리. */
    fun deleteAllByCaseId(errorCaseId: Long): Int
}
