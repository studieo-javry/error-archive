package org.studieojavry.notiapi.notification.infrastructure.kafka

/**
 * noti-api 가 소비하는 알림 이벤트 종류 ↔ Kafka 토픽 매핑.
 *
 * DLQ 저장 시 discriminator 로 쓰이며(어느 use case 로 재처리할지), 재시도 잡이 이 값으로
 * [KafkaEventProcessor.dispatch] 를 호출해 원래 처리 로직을 그대로 재실행한다.
 */
enum class KafkaEventType(val topic: String) {
    MENTION("notification-events.mentions.v1"),
    REPLY("notification-events.replies.v1"),
    COMMENT_ON_CASE("notification-events.comments-on-case.v1"),
    CASE_RESOLVED("notification-events.case-resolved.v1"),
    FOLLOW("notification-events.follows.v1"),
    WORKSPACE_INVITATION("notification-events.invitations.v1"),
    ;

    companion object {
        fun fromTopic(topic: String): KafkaEventType? = entries.firstOrNull { it.topic == topic }
    }
}
