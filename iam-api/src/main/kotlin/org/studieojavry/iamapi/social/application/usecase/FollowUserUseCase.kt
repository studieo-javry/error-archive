package org.studieojavry.iamapi.social.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.shared.notification.application.port.NotificationPublisherPort
import org.studieojavry.iamapi.social.application.command.FollowUserCommand
import org.studieojavry.iamapi.social.application.port.ActivityEventPublisherPort
import org.studieojavry.iamapi.social.application.port.FollowRepositoryPort
import org.studieojavry.iamapi.social.application.port.UserSummaryReaderPort
import org.studieojavry.iamapi.social.domain.model.Follow
import java.time.Instant

@Service
class FollowUserUseCase(
    private val followRepository: FollowRepositoryPort,
    private val userSummaryReader: UserSummaryReaderPort,
    private val activityEventPublisher: ActivityEventPublisherPort,
    private val notificationPublisher: NotificationPublisherPort,
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

        // 잔디용 activity event — outbox INSERT (도메인 트랜잭션과 atomic). 새로 생성된 경우만.
        activityEventPublisher.publish(
            ActivityEventPublisherPort.ActivityEvent(
                userId = command.followerId,
                type = ActivityEventPublisherPort.Type.FOLLOWED_USER,
                occurredAt = Instant.now(),
                idempotencyKey = "follow:${command.followerId}:${command.followeeId}",
                meta = mapOf("followeeId" to command.followeeId),
            )
        )

        // 새 팔로워 알림 — followee 에게 in-app + email (mention 과 다른 토글).
        notificationPublisher.publishNewFollower(
            NotificationPublisherPort.NewFollowerEvent(
                recipientUserId = command.followeeId,
                followerUserId = command.followerId,
            )
        )

        return Result(created = true)
    }

    data class Result(val created: Boolean)
}
