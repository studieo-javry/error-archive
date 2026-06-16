package org.studieojavry.coreapi.errorcase.case.application.port

import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import java.time.LocalDateTime

@Repository
interface ErrorCaseRepositoryPort {

    fun save(errorCase: ErrorCase): ErrorCase

    /** 전체 애그리거트 로드(스냅샷+스니펫+첨부 포함). 없으면 null. */
    fun findById(errorCaseId: Long): ErrorCase?

    /** 메타데이터/본문만 갱신(스니펫·첨부 연결은 건드리지 않음). */
    fun update(errorCase: ErrorCase): ErrorCase

    /** 목록 조회(요약). keyset 커서 기반, createdAt DESC, id DESC. limit 만큼 반환. */
    fun search(criteria: ErrorCaseSearchCriteria): List<ErrorCaseSummary>

    fun findOwnerUserIdById(errorCaseId: Long): Long?

    fun deleteById(errorCaseId: Long)

    /** caseIds 의 summary 일괄. watchlist-feed 에서 case 메타 일괄 조회용. */
    fun findSummariesByIds(caseIds: Collection<Long>): List<ErrorCaseSummary>

    // ── Recent Activity (home dashboard) ──────────────────────
    /** 내 케이스 중 updatedAt DESC, id DESC 순 limit 개. 활동 식별 source. */
    fun findRecentByOwner(ownerUserId: Long, limit: Int): List<ErrorCaseSummary>

    /** ownerUserId 내가 actor 로서 RESOLVED 한 case 들 (since 이후). resolvedAt DESC, id DESC. */
    fun findRecentlyResolvedByActor(actorUserId: Long, since: LocalDateTime, limit: Int): List<ErrorCaseSummary>

    /** since 이후 ownerUserId 가 생성한 case 수. activity summary 산정용. */
    fun countCreatedByOwnerSince(ownerUserId: Long, since: LocalDateTime): Long

    /** since 이후 actorUserId 가 RESOLVED 로 전환한 case 수. activity summary 산정용. */
    fun countResolvedByActorSince(actorUserId: Long, since: LocalDateTime): Long
}
