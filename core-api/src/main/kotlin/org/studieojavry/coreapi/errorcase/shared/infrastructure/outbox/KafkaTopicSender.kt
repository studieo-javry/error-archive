package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Outbox row → Kafka send 의 하단 컴포넌트.
 *
 * `OutboxRelayer` 가 매 라운드 호출. 실패하면 throw 해서 Relayer 가 backoff 처리 (catch X).
 *
 * `send().get()` 으로 broker ack 대기. 옵션값들 (delivery/request timeout) 은 KafkaProducerConfig 의
 * yml 매핑으로 이미 ms 단위 짧게 설정 — outbox latency 가 broker 장애로 쌓이지 않도록.
 *
 * mode != kafka 일 때는 빈 등록 안 됨 → 그 환경에선 OutboxRelayer 도 비활성화 (같은 conditional).
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class KafkaTopicSender(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    /**
     * @throws Exception broker 실패. caller 가 retry 결정.
     */
    fun send(topic: String, key: String?, payload: String) {
        // get() 으로 ack 까지 대기 — relayer 는 작은 batch 만 처리하므로 직렬화 OK.
        // 추후 throughput 한계 시 async + future 묶음 처리로 변경 가능.
        // key null = round-robin partition (key 있는 오버로드는 non-null 요구).
        val future = if (key != null) {
            kafkaTemplate.send(topic, key, payload)
        } else {
            kafkaTemplate.send(topic, payload)
        }
        future.get(10, TimeUnit.SECONDS)
    }
}
