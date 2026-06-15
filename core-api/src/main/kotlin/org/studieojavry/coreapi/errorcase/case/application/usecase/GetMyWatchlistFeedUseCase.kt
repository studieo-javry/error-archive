package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.CaseActivityRow
import org.studieojavry.coreapi.errorcase.case.application.port.CaseActivitySource
import org.studieojavry.coreapi.errorcase.case.application.port.CaseViewRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import java.time.LocalDateTime

/**
 * 홈 대시보드의 "Watchlist · 업데이트" feed — *내가 watchlist 한 case 들* 의 *최근 활동* 카드 목록.
 *
 * **source 정책** (확정 4종 + 후속 확장):
 *  - CASE_RESOLVED         — case.resolvedAt (virtual). actor = case.resolvedByUserId.
 *  - COMMENT_POSTED        — comment 테이블. actor = comment.authorUserId. deleted 제외.
 *  - STEP_ADDED            — step 테이블. actor = step.authorUserId.
 *  - SOLUTION_REGISTERED   — solution 테이블. actor = solution.authorUserId.
 *  - CASE_PUBLISHED        — publish-api 연동 완료 후 추가 (현재 enum 에 주석 처리됨).
 *
 * 정렬: (unreadActivityCount > 0 desc, lastActivityAt desc). 안 본 업데이트 있는 카드 상단.
 * limit: 20 기본, 1~100.
 */
@Service
class GetMyWatchlistFeedUseCase(
    private val watchlistRepository: CaseWatchlistRepositoryPort,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val caseViewRepository: CaseViewRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val limit = input.limit.coerceIn(1, MAX_LIMIT)

        // 1. 내 watchlist 의 candidate case-id list
        val candidateCaseIds = watchlistRepository.findCaseIdsByUserId(input.userId, CANDIDATE_WINDOW)
        if (candidateCaseIds.isEmpty()) return Result(items = emptyList())

        val summaries = errorCaseRepository.findSummariesByIds(candidateCaseIds)
            .associateBy { it.id }

        // 2. 3 sources batch projection — source enum 식별 가능. case 별 latest row 추출.
        val activities = commentRepository.findActivitiesByCaseIdsSince(candidateCaseIds, EPOCH) +
            stepRepository.findActivitiesByCaseIdsSince(candidateCaseIds, EPOCH) +
            solutionRepository.findActivitiesByCaseIdsSince(candidateCaseIds, EPOCH)

        // case-id 별 *전체 source 중 latest row* (3 sources fan-in)
        val latestRowByCase: Map<Long, CaseActivityRow> = activities
            .groupBy { it.errorCaseId }
            .mapValues { (_, rows) -> rows.maxBy { it.createdAt } }

        // 3. unread 계산 — author != me + createdAt > lastViewedAt
        // 3 sources (comment/step/solution) + CASE_RESOLVED virtual (case.resolvedAt 가 lastViewed 이후)
        val lastViewedByCase = caseViewRepository.findByUserAndCaseIds(input.userId, candidateCaseIds)
        val realActivityUnread: Map<Long, Long> = activities
            .filter { it.authorUserId != input.userId }
            .filter {
                val lastViewed = lastViewedByCase[it.errorCaseId]
                lastViewed == null || it.createdAt.isAfter(lastViewed)
            }
            .groupingBy { it.errorCaseId }
            .eachCount()
            .mapValues { it.value.toLong() }
        // CASE_RESOLVED virtual unread — resolvedAt > lastViewed AND resolvedBy != me
        val resolvedUnread: Map<Long, Long> = candidateCaseIds.mapNotNull { caseId ->
            val summary = summaries[caseId] ?: return@mapNotNull null
            val resolvedAt = summary.resolvedAt ?: return@mapNotNull null
            if (summary.resolvedByUserId == input.userId) return@mapNotNull null
            val lastViewed = lastViewedByCase[caseId]
            if (lastViewed != null && !resolvedAt.isAfter(lastViewed)) return@mapNotNull null
            caseId to 1L
        }.toMap()
        val unreadByCase: Map<Long, Long> = (realActivityUnread.keys + resolvedUnread.keys)
            .associateWith { (realActivityUnread[it] ?: 0L) + (resolvedUnread[it] ?: 0L) }

        // 4. case-id 별 latest 결정 — 3 sources latest 와 CASE_RESOLVED virtual 비교
        val cards = candidateCaseIds.mapNotNull { caseId ->
            val summary = summaries[caseId] ?: return@mapNotNull null
            val latest = pickLatestActivity(summary, latestRowByCase[caseId])
                ?: return@mapNotNull null
            Item(
                summary = summary,
                latestActivitySource = latest.source,
                latestActivityId = latest.activityId,
                latestActivityActorUserId = latest.actorUserId,
                lastActivityAt = latest.occurredAt,
                unreadActivityCount = unreadByCase[caseId] ?: 0L,
            )
        }

        val sorted = cards.sortedWith(
            compareByDescending<Item> { it.unreadActivityCount > 0L }
                .thenByDescending { it.lastActivityAt }
        ).take(limit)

        return Result(items = sorted)
    }

    /**
     * case-id 별 latest activity 결정:
     *  - 3 sources (comment/step/solution) latest row
     *  - + CASE_RESOLVED virtual (case.resolvedAt 가 있으면)
     *  중 가장 최근(occurredAt max) 1건.
     *
     * 후속: publish-api 연동 완료 후 CASE_PUBLISHED virtual 도 같은 방식으로 추가.
     */
    private fun pickLatestActivity(
        summary: ErrorCaseSummary,
        latestActivityRow: CaseActivityRow?,
    ): LatestActivity? {
        val candidates = mutableListOf<LatestActivity>()
        latestActivityRow?.let {
            candidates += LatestActivity(
                source = it.source,
                activityId = it.activityId,
                actorUserId = it.authorUserId,
                occurredAt = it.createdAt,
            )
        }
        if (summary.resolvedAt != null) {
            candidates += LatestActivity(
                source = CaseActivitySource.CASE_RESOLVED,
                activityId = null,           // virtual — case.resolvedAt 자체. 별도 PK 없음
                actorUserId = summary.resolvedByUserId,
                occurredAt = summary.resolvedAt,
            )
        }
        return candidates.maxByOrNull { it.occurredAt }
    }

    private data class LatestActivity(
        val source: CaseActivitySource,
        val activityId: Long?,
        val actorUserId: Long?,
        val occurredAt: LocalDateTime,
    )

    data class Input(
        val userId: Long,
        val limit: Int = DEFAULT_LIMIT,
    )

    data class Item(
        val summary: ErrorCaseSummary,
        val latestActivitySource: CaseActivitySource,
        /** 도메인별 PK — COMMENT/STEP/SOLUTION 의 row id. CASE_RESOLVED 는 virtual 이라 null. deep link fragment 용. */
        val latestActivityId: Long?,
        /** type 별 정확 매핑: CASE_RESOLVED → resolvedBy, COMMENT_POSTED/STEP_ADDED/SOLUTION_REGISTERED → 작성자. */
        val latestActivityActorUserId: Long?,
        val lastActivityAt: LocalDateTime,
        /** 사용자가 case 상세를 마지막으로 본 시각 이후 일어난 *다른 사람* 활동 수. */
        val unreadActivityCount: Long,
    )

    data class Result(val items: List<Item>)

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100
        /** watchlist 의 최근 N개까지 candidate. limit 보다 크게 잡아 sort 여유. */
        private const val CANDIDATE_WINDOW = 50
        private val EPOCH: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
    }
}
