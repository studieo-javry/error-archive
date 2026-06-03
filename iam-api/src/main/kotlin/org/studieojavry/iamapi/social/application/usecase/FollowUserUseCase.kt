package org.studieojavry.iamapi.social.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.social.application.command.FollowUserCommand
import org.studieojavry.iamapi.social.application.port.FollowRepositoryPort
import org.studieojavry.iamapi.social.application.port.UserSummaryReaderPort
import org.studieojavry.iamapi.social.domain.model.Follow

@Service
class FollowUserUseCase(
    private val followRepository: FollowRepositoryPort,
    private val userSummaryReader: UserSummaryReaderPort
) {

    /**
     * GitHub 스타일: 즉시 팔로우, 승인 절차 없음. 멱등(이미 팔로우 중이면 no-op).
     * @return true = 새로 생성됨, false = 이미 존재해서 변동 없음
     */
    @Transactional
    fun invoke(command: FollowUserCommand): Result {
        require(command.followerId != command.followeeId) { "cannot follow self" }
        if (!userSummaryReader.existsActive(command.followeeId)) {
            throw NoSuchElementException("user not found: ${command.followeeId}")
        }

        if (followRepository.exists(command.followerId, command.followeeId)) {
            return Result(created = false)
        }
        followRepository.save(Follow.create(command.followerId, command.followeeId))
        // TODO: 추후 noti-api 연동 — 도메인 이벤트(UserFollowed) 발행
        return Result(created = true)
    }

    data class Result(val created: Boolean)
}
