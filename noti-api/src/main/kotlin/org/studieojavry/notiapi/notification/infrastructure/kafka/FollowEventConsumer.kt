package org.studieojavry.notiapi.notification.infrastructure.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.ReceiveNewFollowerEventUseCase
import tools.jackson.databind.ObjectMapper

/**
 * `notification-events.follows.v1` 토픽 consumer.
 * iam-api 의 OutboxNotificationPublisherAdapter 가 발행. 단건 처리.
 */
@Component
class FollowEventConsumer(
    private val receiveNewFollowerEventUseCase: ReceiveNewFollowerEventUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = ["notification-events.follows.v1"], groupId = "noti-api")
    fun onFollow(value: String) {
        try {
            val msg = objectMapper.readValue(value, FollowerMessage::class.java)
            receiveNewFollowerEventUseCase.invoke(ReceiveNewFollowerEventUseCase.NewFollowerEvent(
                recipientUserId = msg.recipientUserId,
                followerUserId = msg.followerUserId,
            ))
        } catch (ex: Exception) {
            log.error(ex) { "failed to process follower event: $value" }
            throw ex
        }
    }

    data class FollowerMessage(
        val recipientUserId: Long,
        val followerUserId: Long,
    )
}
