package org.studieojavry.iamapi.social.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.social.application.command.UnfollowUserCommand
import org.studieojavry.iamapi.social.application.port.FollowRepositoryPort

@Service
class UnfollowUserUseCase(
    private val followRepository: FollowRepositoryPort
) {

    /**
     * 멱등(팔로우 관계가 없어도 정상 응답).
     * @return true = 실제로 삭제됨, false = 원래 없었음
     */
    @Transactional
    fun invoke(command: UnfollowUserCommand): Result {
        if (command.followerId == command.followeeId) return Result(removed = false)
        val removed = followRepository.delete(command.followerId, command.followeeId)
        return Result(removed = removed)
    }

    data class Result(val removed: Boolean)
}
