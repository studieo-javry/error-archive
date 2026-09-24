package org.studieojavry.insightapi.activity.infrastructure.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * Spring Boot 4 의 KafkaAutoConfiguration 부재 → ConsumerFactory + listenerContainerFactory 직접 등록.
 * `@KafkaListener` 가 default 로 `kafkaListenerContainerFactory` 빈을 찾으므로 이름 고정.
 *
 * **오프셋 커밋 = 컨테이너 관리(auto-commit=false)**: 리스너가 정상 반환한 배치만 오프셋 커밋.
 * 처리·DLQ 저장이 모두 실패해(예: insight DB 다운) 예외가 리스너 밖으로 나오면, 아래 에러핸들러가
 * **무한 재시도**(skip 안 함)하므로 실패 레코드의 오프셋은 커밋되지 않는다 → **DB 복구 시 재처리, 유실 0**.
 * (기존: auto-commit=true + DefaultErrorHandler 기본 10회 소진 후 skip → DLQ 저장도 DB 다운으로 실패한
 *  레코드가 오프셋만 전진해 **영구 유실**. 2026-09-23 경계검증 #1 에서 실측.)
 * parse 오류는 `onEvent` 안에서 DLQ DEAD 처리 후 정상 반환하므로 에러핸들러까지 오지 않는다.
 */
@Configuration
@EnableKafka
class KafkaConsumerConfig {

    @Bean
    fun activityConsumerFactory(
        @Value("\${spring.kafka.bootstrap-servers:localhost:9094}") bootstrapServers: String,
        @Value("\${spring.kafka.consumer.group-id:insight-api}") groupId: String,
        @Value("\${spring.kafka.consumer.auto-offset-reset:earliest}") autoOffsetReset: String,
    ): ConsumerFactory<String, String> {
        val props = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to autoOffsetReset,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        )
        return DefaultKafkaConsumerFactory(props)
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(consumerFactory)
        // 컨테이너가 오프셋 커밋 관리(정상 처리분만 커밋).
        factory.containerProperties.ackMode = ContainerProperties.AckMode.BATCH
        // 리스너 밖으로 나온 예외(= 처리+DLQ 저장 모두 실패, 사실상 DB 다운)는 skip 시 유실.
        // 무한 재시도(2s backoff)로 오프셋을 전진시키지 않아 DB 복구까지 대기 → 유실 0.
        factory.setCommonErrorHandler(DefaultErrorHandler(FixedBackOff(2_000L, Long.MAX_VALUE)))
        return factory
    }
}
