package org.studieojavry.iamapi.social.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.social.application.port.FollowRepositoryPort

/**
 * **Internal-only** — userId 가 *팔로우 중인* followee userIds 배치 조회.
 *
 * core-api 등이 home 의 "Following · 새 케이스" feed 채울 때 사용.
 * 권한 검증 없음 — internal JWT 통과 호출만 도달.
 */
@Service
class ListFollowingIdsUseCase(
    private val followRepository: FollowRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long, size: Int): List<Long> {
        val bounded = size.coerceIn(1, MAX_SIZE)
        return followRepository.findFollowingIds(userId, 0, bounded).items
    }

    companion object {
        private const val MAX_SIZE = 1000
    }
}
