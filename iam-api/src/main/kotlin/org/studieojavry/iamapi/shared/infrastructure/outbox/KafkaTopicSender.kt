package org.studieojavry.iamapi.shared.infrastructure.outbox

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Outbox row → Kafka send. OutboxRelayer 가 매 라운드 호출. 실패 시 throw.
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class KafkaTopicSender(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    fun send(topic: String, key: String?, payload: String) {
        val future = if (key != null) {
            kafkaTemplate.send(topic, key, payload)
        } else {
            kafkaTemplate.send(topic, payload)
        }
        future.get(10, TimeUnit.SECONDS)
    }
}
