package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.CaseMeTooRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.CaseViewRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.shared.config.CacheConfig
import java.time.LocalDateTime

/**
 * 홈 대시보드의 "My Recent Active Cases" — 내가 만든 케이스 중 최근 N일 안에 활동이 있는 것.
 *
 * 활동 정의 (4 sources max):
 *  - case.updatedAt (제목/본문/status 변경 시 갱신)
 *  - 해당 case 의 comment.createdAt (deleted 제외)
 *  - 해당 case 의 step.createdAt
 *  - 해당 case 의 solution.createdAt
 *
 * **MVP 한계**: 내 케이스 중 *최근 updatedAt DESC 50건* 만 활동 후보로 본다. 50건 밖에서 댓글만
 * 일어난 매우 오래된 케이스는 누락된다 (현실에서 일반적이지 않음). 정확도 향상은 후속에서
 * `case touch on activity` 정책 도입 후 가능.
 *
 * 정렬: (unreadCount > 0 desc, lastActivityAt desc). "안 본 활동 있는 케이스" 가 항상 상단.
 * unread 계산은 candidate 전체에 batch projection 3 query 로 — N+1 없음.
 *
 * limit: 5 (홈 카드 기본 5장).
 */
@Service
class GetMyRecentActiveCasesUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val caseViewRepository: CaseViewRepositoryPort,
    private val caseMeTooRepository: CaseMeTooRepositoryPort,
) {
    /**
     * key = `userId:windowDays:limit` — 다른 param 요청이 서로의 결과를 오염시키지 않도록 param 포함.
     * TTL 30초. mutation 시 `DashboardCacheInvalidator.evictRecentActive(userId)` 가 그 userId 의
     * 모든 param variant 를 prefix 로 제거해 무효화한다.
     */
    @Cacheable(
        cacheNames = [CacheConfig.CACHE_RECENT_ACTIVE_CASES],
        key = "#input.userId + ':' + #input.windowDays + ':' + #input.limit",
    )
    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val limit = input.limit.coerceIn(1, MAX_LIMIT)
        val sinceInstant = LocalDateTime.now().minusDays(input.windowDays.toLong())

        val candidates = errorCaseRepository.findRecentByOwner(input.userId, CANDIDATE_WINDOW)
        if (candidates.isEmpty()) return Result(items = emptyList())

        val caseIds = candidates.mapNotNull { it.id }.toSet()
        val commentMaxByCase = commentRepository.findMaxCreatedAtByCaseIds(caseIds)
        val stepMaxByCase = stepRepository.findMaxCreatedAtByCaseIds(caseIds)
        val solutionMaxByCase = solutionRepository.findMaxCreatedAtByCaseIds(caseIds)
        val commentCountByCase = commentRepository.countByCaseIds(caseIds)
        val stepCountByCase = stepRepository.countByCaseIds(caseIds)
        val solutionCountByCase = solutionRepository.countByCaseIds(caseIds)

        // 1. 모든 candidate 의 lastActivityAt 계산 + 윈도우 필터
        val withinWindow = candidates
            .mapNotNull { summary ->
                val id = summary.id
                val lastActivityAt = listOfNotNull(
                    summary.updatedAt,
                    commentMaxByCase[id],
                    stepMaxByCase[id],
                    solutionMaxByCase[id],
                ).max()
                if (lastActivityAt.isBefore(sinceInstant)) null
                else summary to lastActivityAt
            }
        if (withinWindow.isEmpty()) return Result(items = emptyList())

        val activeCandidateCaseIds = withinWindow.map { it.first.id }
        val lastViewedAtByCase = caseViewRepository.findByUserAndCaseIds(input.userId, activeCandidateCaseIds)

        // 2. unread 계산 — batch projection 3 query. case 별 lastViewedAt + author != me 필터는 in-memory.
        val globalSince = if (activeCandidateCaseIds.any { lastViewedAtByCase[it] == null }) EPOCH
                          else lastViewedAtByCase.values.min() ?: EPOCH
        val allActivities =
            commentRepository.findActivitiesByCaseIdsSince(activeCandidateCaseIds, globalSince) +
                stepRepository.findActivitiesByCaseIdsSince(activeCandidateCaseIds, globalSince) +
                solutionRepository.findActivitiesByCaseIdsSince(activeCandidateCaseIds, globalSince)
        val unreadByCase: Map<Long, Long> = allActivities
            .filter { it.authorUserId != input.userId }
            .filter {
                val lastViewed = lastViewedAtByCase[it.errorCaseId]
                lastViewed == null || it.createdAt.isAfter(lastViewed)
            }
            .groupingBy { it.errorCaseId }
            .eachCount()
            .mapValues { it.value.toLong() }

        // 3. (unreadCount > 0 desc, lastActivityAt desc) 정렬 → take(limit)
        val withLastActivity = withinWindow
            .sortedWith(
                compareByDescending<Pair<ErrorCaseSummary, LocalDateTime>> {
                    (unreadByCase[it.first.id] ?: 0L) > 0L
                }.thenByDescending { it.second }
            )
            .take(limit)

        val topCaseIds = withLastActivity.map { it.first.id }

        // 4. delta — 카드별 since 고정 (24h / 7d). batch group 으로 4 query.
        val now = LocalDateTime.now()
        val since24h = now.minusHours(24)
        val since7d = now.minusDays(7)
        val meTooTotalByCase = caseMeTooRepository.countByCaseIds(topCaseIds)
        val commentDelta24hByCase = commentRepository.countByCaseIdsSince(topCaseIds, since24h)
        val commentDelta7dByCase = commentRepository.countByCaseIdsSince(topCaseIds, since7d)
        val meTooDelta24hByCase = caseMeTooRepository.countByCaseIdsSince(topCaseIds, since24h)
        val meTooDelta7dByCase = caseMeTooRepository.countByCaseIdsSince(topCaseIds, since7d)

        val items = withLastActivity.map { (summary, lastActivityAt) ->
            val id = summary.id
            Item(
                summary = summary,
                stepCount = stepCountByCase[id] ?: 0L,
                commentCount = commentCountByCase[id] ?: 0L,
                solutionCount = solutionCountByCase[id] ?: 0L,
                meTooCount = meTooTotalByCase[id] ?: 0L,
                lastActivityAt = lastActivityAt,
                unreadCount = unreadByCase[id] ?: 0L,
                commentDelta24h = commentDelta24hByCase[id] ?: 0L,
                commentDelta7d = commentDelta7dByCase[id] ?: 0L,
                meTooDelta24h = meTooDelta24hByCase[id] ?: 0L,
                meTooDelta7d = meTooDelta7dByCase[id] ?: 0L,
            )
        }

        return Result(items = items)
    }

    data class Input(
        val userId: Long,
        /** 활동 기준 윈도우 (기본 7d). */
        val windowDays: Int = DEFAULT_WINDOW_DAYS,
        /** 응답 카드 수. */
        val limit: Int = DEFAULT_LIMIT,
    )

    data class Item(
        val summary: ErrorCaseSummary,
        val stepCount: Long,
        val commentCount: Long,
        val solutionCount: Long,
        val meTooCount: Long,
        val lastActivityAt: LocalDateTime,
        /** 사용자가 case 상세를 마지막으로 본 시각 이후 일어난 *다른 사람* 활동 수 (comment+step+solution). */
        val unreadCount: Long,
        /** 지난 24h 동안 새로 달린 댓글 수 (작성자 무관). 카드의 "hot" 신호. */
        val commentDelta24h: Long,
        /** 지난 7d 동안 새로 달린 댓글 수. */
        val commentDelta7d: Long,
        /** 지난 24h 동안 새로 추가된 me-too 수. */
        val meTooDelta24h: Long,
        /** 지난 7d 동안 새로 추가된 me-too 수. */
        val meTooDelta7d: Long,
    )

    data class Result(val items: List<Item>)

    companion object {
        private const val DEFAULT_WINDOW_DAYS = 7
        private const val DEFAULT_LIMIT = 5
        private const val MAX_LIMIT = 20
        /** 활동 후보로 보는 내 케이스 최근 updatedAt N건. */
        private const val CANDIDATE_WINDOW = 50
        /** `lastViewed == null` 일 때 사용할 하한선. `LocalDateTime.MIN` 은 Postgres timestamp 범위 밖. */
        private val EPOCH: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
    }
}
