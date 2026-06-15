package org.studieojavry.coreapi.errorcase.case.application.port

import org.studieojavry.coreapi.errorcase.case.domain.model.CaseWatchlist

interface CaseWatchlistRepositoryPort {

    /** 멱등 — 이미 있으면 기존 1건 반환, 없으면 새로 저장. */
    fun add(errorCaseId: Long, userId: Long): CaseWatchlist

    /** 없어도 0 반환 (멱등). */
    fun remove(errorCaseId: Long, userId: Long): Int

    fun exists(errorCaseId: Long, userId: Long): Boolean

    /** 알림 수신자 list — RESOLVED 등 활동 발생 시 호출. */
    fun findUserIdsByCaseId(errorCaseId: Long): List<Long>

    /** 내가 watchlist 한 case-id 목록. createdAt DESC. limit. */
    fun findCaseIdsByUserId(userId: Long, limit: Int): List<Long>

    /** 케이스 삭제 시 cascade 정리. */
    fun deleteAllByCaseId(errorCaseId: Long): Int

    /**
     * userId 가 watchlist 한 case 들에 *함께 watchlist* 한 *다른* userId 들 + 겹침 case 수.
     * suggested-followees 의 *L* 신호. count DESC 정렬, limit.
     */
    fun findCoOccurringUserIds(userId: Long, limit: Int): Map<Long, Long>
}
