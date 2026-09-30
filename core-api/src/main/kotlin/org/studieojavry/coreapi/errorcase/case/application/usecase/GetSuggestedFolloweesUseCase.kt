package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort
import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort.AuthorSummary
import org.studieojavry.coreapi.errorcase.case.application.port.CaseMeTooRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.FollowedUserReaderPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceMemberReaderPort
import org.studieojavry.coreapi.shared.config.CacheConfig
import java.time.LocalDateTime

/**
 * suggested-followees — *내가 follow 하지 않은 다른 사용자* 중 추천.
 *
 * **신호 + 가중치** (확정 spec):
 *  - **M** (me-too overlap)     : weight 5 — 같은 케이스 me-too 한 사용자 수
 *  - **L** (watchlist overlap)  : weight 4 — 같은 케이스 watchlist 한 사용자 수
 *  - **W** (workspace co-member): weight 3 — 같은 워크스페이스 멤버 (count 무관, 1 회 가산)
 *
 * **score** = Σ(signal × weight × overlap_count)
 *
 * **필터링**: 본인 / 이미 follow / inactive 제외 (inactive 는 author hydration 시 누락됨)
 *
 * **cold-start fallback (2단)**:
 *  1) top contributor — 최근 30d PUBLIC case 작성자 by count desc
 *  2) random shuffle  — top contributor 도 비면 PUBLIC case 작성자 중 random pick (시간 윈도우 없음)
 *
 * 시스템에 PUBLIC case 가 0 개면 최종적으로 empty.
 *
 * **캐싱**: 15분 TTL, key=userId (CacheConfig.CACHE_SUGGESTED_FOLLOWEES).
 */
@Service
class GetSuggestedFolloweesUseCase(
    private val meTooRepository: CaseMeTooRepositoryPort,
    private val watchlistRepository: CaseWatchlistRepositoryPort,
    private val workspaceMemberReader: WorkspaceMemberReaderPort,
    private val followedUserReader: FollowedUserReaderPort,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val authorSummaryReader: AuthorSummaryReaderPort,
    private val suggestionFallbackPool: SuggestionFallbackPool,
    @Qualifier("fanoutExecutor") private val fanoutExecutor: ExecutorService,
) {
    @Cacheable(cacheNames = [CacheConfig.CACHE_SUGGESTED_FOLLOWEES], key = "#input.userId.toString() + ':' + #input.limit")
    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val limit = input.limit.coerceIn(1, MAX_LIMIT)

        // 1~2. 제외 set(①) + 신호 3종(②③④) — 서로 독립이라 **fan-out 병렬**(가상 스레드).
        //       기존엔 순차라 지연 = ①+②+③+④ 합. 병렬 시 지연 = max(①,②,③,④).
        val excludedF = CompletableFuture.supplyAsync({
            followedUserReader.findFollowingIds(input.userId, FOLLOWING_LOOKUP_SIZE).toSet() + input.userId
        }, fanoutExecutor)
        val meTooF = CompletableFuture.supplyAsync({
            meTooRepository.findCoOccurringUserIds(input.userId, SIGNAL_LIMIT)
        }, fanoutExecutor)
        val watchlistF = CompletableFuture.supplyAsync({
            watchlistRepository.findCoOccurringUserIds(input.userId, SIGNAL_LIMIT)
        }, fanoutExecutor)
        val workspaceF = CompletableFuture.supplyAsync({
            workspaceMemberReader.findCoMemberIds(input.userId, SIGNAL_LIMIT)
        }, fanoutExecutor)

        val excluded = excludedF.join()
        val meTooOverlap = meTooF.join()
        val watchlistOverlap = watchlistF.join()
        val workspaceCoMembers = workspaceF.join()

        // 3. 후보별 score 계산
        val candidates: Map<Long, Long> = buildMap<Long, Long> {
            meTooOverlap.forEach { (uid, count) ->
                if (uid !in excluded) merge(uid, count * WEIGHT_ME_TOO, Long::plus)
            }
            watchlistOverlap.forEach { (uid, count) ->
                if (uid !in excluded) merge(uid, count * WEIGHT_WATCHLIST, Long::plus)
            }
            workspaceCoMembers.forEach { uid ->
                if (uid !in excluded) merge(uid, WEIGHT_WORKSPACE, Long::plus)
            }
        }

        // 4. score desc 정렬 → top N
        val signalRanked = candidates.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

        // 5. fallback — 결과 부족 시 (a) top contributor → (b) random shuffle 순으로 채움
        var resultIds = signalRanked
        if (resultIds.size < limit) {
            val needed = limit - resultIds.size
            // 전역 top-authors 는 userId 무관·결정적 → 캐시된 풀에서 취함(요청당 full-scan 제거).
            val coldStart = suggestionFallbackPool.topRecentPublicAuthors()
                .asSequence()
                .filter { it !in excluded }
                .filter { it !in resultIds }
                .take(needed)
                .toList()
            resultIds = resultIds + coldStart
        }
        if (resultIds.size < limit) {
            val needed = limit - resultIds.size
            val randomPool = errorCaseRepository
                .findRandomPublicCaseOwners(needed * 4)
                .asSequence()
                .filter { it !in excluded }
                .filter { it !in resultIds }
                .take(needed)
                .toList()
            resultIds = resultIds + randomPool
        }

        if (resultIds.isEmpty()) return Result(items = emptyList(), authors = emptyMap())

        // 6. author hydration (handle / displayName / avatarUrl / bio / isFollowing)
        val authors = authorSummaryReader.read(resultIds.toSet(), input.userId)
        return Result(items = resultIds, authors = authors)
    }

    data class Input(
        val userId: Long,
        val limit: Int = DEFAULT_LIMIT,
    )

    data class Result(
        /** score desc 정렬된 userId list. fallback 시 cold-start 가 뒤에 붙음. */
        val items: List<Long>,
        val authors: Map<Long, AuthorSummary>,
    )

    companion object {
        private const val DEFAULT_LIMIT = 3
        private const val MAX_LIMIT = 20

        private const val WEIGHT_ME_TOO = 5L
        private const val WEIGHT_WATCHLIST = 4L
        private const val WEIGHT_WORKSPACE = 3L

        private const val SIGNAL_LIMIT = 100
        private const val FOLLOWING_LOOKUP_SIZE = 500
        private const val COLD_START_WINDOW_DAYS = 30
    }
}
