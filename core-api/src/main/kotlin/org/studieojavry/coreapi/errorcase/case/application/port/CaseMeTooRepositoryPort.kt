package org.studieojavry.coreapi.errorcase.case.application.port

import org.studieojavry.coreapi.errorcase.case.domain.model.CaseMeToo
import java.time.LocalDateTime

interface CaseMeTooRepositoryPort {

    /** 멱등 — 이미 있으면 기존 1건 반환, 없으면 새로 저장 후 반환. */
    fun add(errorCaseId: Long, userId: Long): CaseMeToo

    /** 없어도 0 반환 (멱등). */
    fun remove(errorCaseId: Long, userId: Long): Int

    fun listByCaseId(errorCaseId: Long): List<CaseMeToo>

    fun count(errorCaseId: Long): Long

    /** case-id 별 me-too 총 수 (일괄). 카드 표시용. */
    fun countByCaseIds(caseIds: Collection<Long>): Map<Long, Long>

    /** case-id 별 since 이후 새 me-too 수 (일괄). delta 산정용. */
    fun countByCaseIdsSince(caseIds: Collection<Long>, since: LocalDateTime): Map<Long, Long>

    /**
     * userId 가 me-too 한 case 들에 *함께 me-too* 한 *다른* userId 들 + 겹침 case 수.
     * suggested-followees 의 *M* 신호. count DESC 정렬, limit.
     */
    fun findCoOccurringUserIds(userId: Long, limit: Int): Map<Long, Long>

    fun exists(errorCaseId: Long, userId: Long): Boolean

    /** 케이스 삭제 시 cascade 정리. */
    fun deleteAllByCaseId(errorCaseId: Long): Int
}
