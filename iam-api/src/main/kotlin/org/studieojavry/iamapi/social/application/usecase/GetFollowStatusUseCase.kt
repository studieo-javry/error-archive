package org.studieojavry.iamapi.social.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.social.application.port.FollowRepositoryPort
import org.studieojavry.iamapi.social.application.port.UserSummaryReaderPort

@Service
class GetFollowStatusUseCase(
    private val followRepository: FollowRepositoryPort,
    private val userSummaryReader: UserSummaryReaderPort
) {

    /**
     * targetUserId 사용자에 대한 정보:
     * - followersCount / followingCount
     * - viewerUserId가 null이 아니면: viewer가 target을 팔로우 중인지(`viewerFollowsTarget`),
     *   target이 viewer를 팔로우 중인지(`targetFollowsViewer`).
     */
    @Transactional(readOnly = true)
    fun invoke(targetUserId: Long, viewerUserId: Long?): Result {
        if (!userSummaryReader.existsActive(targetUserId)) {
            throw NoSuchElementException("user not found: $targetUserId")
        }
        val followers = followRepository.countFollowers(targetUserId)
        val following = followRepository.countFollowing(targetUserId)

        val viewerFollowsTarget = viewerUserId
            ?.takeIf { it != targetUserId }
            ?.let { followRepository.exists(it, targetUserId) } ?: false
        val targetFollowsViewer = viewerUserId
            ?.takeIf { it != targetUserId }
            ?.let { followRepository.exists(targetUserId, it) } ?: false

        return Result(
            userId = targetUserId,
            followersCount = followers,
            followingCount = following,
            viewerFollowsTarget = viewerFollowsTarget,
            targetFollowsViewer = targetFollowsViewer,
            isMutual = viewerFollowsTarget && targetFollowsViewer,
            isSelf = viewerUserId == targetUserId
        )
    }

    data class Result(
        val userId: Long,
        val followersCount: Long,
        val followingCount: Long,
        val viewerFollowsTarget: Boolean,
        val targetFollowsViewer: Boolean,
        val isMutual: Boolean,
        val isSelf: Boolean
    )
}
