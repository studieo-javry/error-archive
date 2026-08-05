package org.studieojavry.notiapi.notification.infrastructure.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * Spring Boot 4 는 spring-kafka autoconfig 를 별도 starter 로 분리 →
 * `@KafkaListener` 가 동작하려면 ConsumerFactory + `kafkaListenerContainerFactory` 빈을 직접 정의.
 *
 * ## 실패 처리 (DB DLQ + 자동 재처리)
 * consumer 들이 처리 실패를 **직접 catch → [org.studieojavry.notiapi.notification.infrastructure.dlq.NotificationDlqSink]
 * 로 DB 격리 → 정상 return**(offset commit) 한다. PENDING(INGEST) 은
 * [org.studieojavry.notiapi.notification.infrastructure.dlq.NotificationDlqRetryJob] 이 backoff 로 자동 재처리.
 *
 * 아래 `DefaultErrorHandler` 는 **DLQ 저장 자체가 실패**(DB 다운 등)해 예외가 컨테이너까지 올라온
 * 극단적 상황의 안전망 — 몇 번 재시도 후 로그 남기고 스킵(파티션 무한 블록 방지). Kafka `.DLT` 토픽은 쓰지 않는다.
 */
@Configuration
@EnableKafka
class KafkaConsumerConfig {

    private val log = KotlinLogging.logger {}

    @Bean
    fun mentionConsumerFactory(
        @Value("\${spring.kafka.bootstrap-servers:localhost:9094}") bootstrapServers: String,
        @Value("\${spring.kafka.consumer.group-id:noti-api}") groupId: String,
        @Value("\${spring.kafka.consumer.auto-offset-reset:earliest}") autoOffsetReset: String,
    ): ConsumerFactory<String, String> {
        val props = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to autoOffsetReset,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            // 컨테이너가 처리 후 offset 커밋(성공/DLQ격리 후).
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        )
        return DefaultKafkaConsumerFactory(props)
    }

    /** DLQ 저장까지 실패한 극단 상황의 안전망 — 재시도 2회 후 로그+스킵(무한 블록 방지). */
    @Bean
    fun kafkaErrorHandler(): DefaultErrorHandler {
        val handler = DefaultErrorHandler({ record, ex ->
            log.error(ex) { "[kafka] unrecoverable (DLQ sink 실패 추정) topic=${record.topic()} offset=${record.offset()} — 스킵" }
        }, FixedBackOff(1_000L, 2))
        return handler
    }

    /** Spring 이 @KafkaListener 에 default 로 찾는 빈 이름 `kafkaListenerContainerFactory` — 이름 변경 금지. */
    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        kafkaErrorHandler: DefaultErrorHandler,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(consumerFactory)
        factory.setCommonErrorHandler(kafkaErrorHandler)
        return factory
    }
}
