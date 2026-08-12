package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort
import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort.AuthorSummary
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.shared.application.port.FollowedUserReaderPort
import org.studieojavry.coreapi.shared.config.CacheConfig

/**
 * 홈 대시보드의 "Following · 새 케이스" feed — *내가 팔로우한 사용자들의* 최근 PUBLIC 케이스.
 *
 * 흐름:
 *  1. iam-api 호출 — 내가 팔로우 중인 followee userId list
 *  2. core-api 자체 query — 그 owner 들의 *PUBLIC* case 를 createdAt DESC 로 limit
 *  3. author summary hydration (avatar / displayName / handle / bio)
 *
 * followee 0명 → emptyList. 사용자가 누구도 팔로우하지 않은 상태.
 *
 * **캐싱**: `following-feed`, key = `userId:limit`, TTL 60초. 발견용 피드라 stale 60초 허용 —
 * 매 홈 진입마다 iam-api(팔로잉 조회) 왕복을 캐시로 완충한다. evict 대상 아님 (팔로우/신규 case
 * fan-out 비용 회피) — 팔로우 직후 즉시 반영은 FE refetch 로 처리.
 */
@Service
class GetMyFollowingFeedUseCase(
    private val followedUserReader: FollowedUserReaderPort,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val authorSummaryReader: AuthorSummaryReaderPort,
) {
    @Cacheable(cacheNames = [CacheConfig.CACHE_FOLLOWING_FEED], key = "#input.userId + ':' + #input.limit")
    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val limit = input.limit.coerceIn(1, MAX_LIMIT)
        val followingIds = followedUserReader.findFollowingIds(input.userId, FOLLOWING_LOOKUP_SIZE)
        if (followingIds.isEmpty()) return Result(items = emptyList(), authors = emptyMap())

        val items = errorCaseRepository.findRecentPublicByOwners(followingIds, limit)
        if (items.isEmpty()) return Result(items = emptyList(), authors = emptyMap())

        // viewerUserId = null — 피드 항목은 정의상 *내가 팔로우한 사람* 이라 isFollowing 이 항상 true 로 확정.
        // viewer 를 넘기면 iam 이 불필요한 follow-check(existsAll) 쿼리를 돌리므로 생략. 응답 DTO
        // (FollowingFeedAuthorResponse) 도 isFollowing 을 노출하지 않는다.
        val authors = authorSummaryReader.read(items.map { it.ownerUserId }.toSet(), viewerUserId = null)
        return Result(items = items, authors = authors)
    }

    data class Input(
        val userId: Long,
        val limit: Int = DEFAULT_LIMIT,
    )

    data class Result(
        val items: List<ErrorCaseSummary>,
        val authors: Map<Long, AuthorSummary>,
    )

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100
        /** followee 조회 size — 사용자가 많이 팔로우해도 최대 N명. */
        private const val FOLLOWING_LOOKUP_SIZE = 500
    }
}
