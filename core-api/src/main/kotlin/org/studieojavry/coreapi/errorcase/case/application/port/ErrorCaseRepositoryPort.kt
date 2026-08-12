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

    /**
     * `search()` 와 같은 필터 (workspaceId / ownerUserId / q / visibility) 를 그대로 적용하되,
     * status 는 무시하고 status 별 개수를 반환. Library 페이지의 status filter chip 카운트용.
     * cursor / limit / sortBy 는 무의미하므로 무시.
     */
    fun countByStatus(criteria: ErrorCaseSearchCriteria): Map<org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus, Long>

    fun findOwnerUserIdById(errorCaseId: Long): Long?

    fun deleteById(errorCaseId: Long)

    // ── Recent Activity (home dashboard) ──────────────────────
    /** 내 케이스 중 updatedAt DESC, id DESC 순 limit 개. 활동 식별 source. */
    fun findRecentByOwner(ownerUserId: Long, limit: Int): List<ErrorCaseSummary>

    /** caseIds 의 summary 일괄 (lastActivityAt 계산 후 다른 case 가 추가 식별되면 보충). */
    fun findSummariesByIds(caseIds: Collection<Long>): List<ErrorCaseSummary>

    /** ownerUserId 내가 actor 로서 RESOLVED 한 case 들 (since 이후). resolvedAt DESC, id DESC. */
    fun findRecentlyResolvedByActor(actorUserId: Long, since: LocalDateTime, limit: Int): List<ErrorCaseSummary>

    /** since 이후 ownerUserId 가 생성한 case 수. activity summary 산정용. */
    fun countCreatedByOwnerSince(ownerUserId: Long, since: LocalDateTime): Long

    /** since 이후 actorUserId 가 RESOLVED 로 전환한 case 수. activity summary 산정용. */
    fun countResolvedByActorSince(actorUserId: Long, since: LocalDateTime): Long

    /**
     * ownerUserIds 중 PUBLIC 케이스, createdAt DESC, id DESC limit.
     * home 의 "Following · 새 케이스" feed 채울 때 사용.
     */
    fun findRecentPublicByOwners(ownerUserIds: Collection<Long>, limit: Int): List<ErrorCaseSummary>

    /**
     * since 이후 PUBLIC 케이스 *작성자* userIds, *작성 case 수 DESC* 정렬, limit.
     * suggested-followees 의 cold-start fallback (top contributor) 용.
     */
    fun findTopOwnersOfRecentPublic(since: LocalDateTime, limit: Int): List<Long>

    /**
     * PUBLIC 케이스 *작성자* userIds 를 random 순으로 limit. 시간 윈도우 없음.
     * suggested-followees 의 *최후 fallback* — top contributor 마저 0 일 때.
     * 시스템에 PUBLIC case 가 없으면 empty.
     */
    fun findRandomPublicCaseOwners(limit: Int): List<Long>
}
