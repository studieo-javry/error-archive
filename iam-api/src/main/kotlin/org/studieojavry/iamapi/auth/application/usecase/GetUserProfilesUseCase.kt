package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus
import org.studieojavry.iamapi.social.application.port.FollowRepositoryPort

/**
 * 여러 userId 의 *목록 응답용 프로필* 을 batch 로 반환.
 *
 * core-api 의 검색/목록 endpoint 가 결과 항목마다 작성자 정보(avatar/displayName/bio) 와
 * *viewer 의 follow 관계* 까지 한 번에 받아오도록 만든 internal-only endpoint.
 *
 * - 비활성/삭제 사용자는 응답에서 제외 (검색 결과의 ghost 표시 회피)
 * - viewerUserId 가 null 이면 isFollowing 은 모두 false
 * - viewerUserId 가 자기 자신 (== userId) 이면 isFollowing 은 false 로 강제 (자기 자신 follow 의미 없음)
 */
@Service
class GetUserProfilesUseCase(
    private val userRepository: UserRepositoryPort,
    private val followRepository: FollowRepositoryPort,
) {

    @Transactional(readOnly = true)
    fun invoke(userIds: List<Long>, viewerUserId: Long?): List<UserProfile> {
        if (userIds.isEmpty()) return emptyList()

        val distinct = userIds.toSet()
        val users = userRepository.findAllByIds(distinct).filter { it.status == UserStatus.ACTIVE }
        if (users.isEmpty()) return emptyList()

        val followedIds: Set<Long> = if (viewerUserId != null) {
            val targets = users.mapNotNull { it.id }.filter { it != viewerUserId }
            followRepository.existsAll(viewerUserId, targets)
        } else emptySet()

        return users.map { u ->
            UserProfile(
                userId = u.id!!,
                handle = u.handle,
                displayName = u.displayName,
                avatarUrl = u.avatarUrl,
                bio = u.bio,
                isFollowing = u.id in followedIds,
            )
        }
    }

    data class UserProfile(
        val userId: Long,
        val handle: String,
        val displayName: String,
        val avatarUrl: String?,
        val bio: String?,
        val isFollowing: Boolean,
    )
}
