package org.studieojavry.notiapi.notification.infrastructure.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory

/**
 * Spring Boot 4 는 spring-kafka 의 KafkaAutoConfiguration 을 별도 starter 로 분리했고
 * 현재 의존성에는 자동 설정이 없다 — `@KafkaListener` 가 동작하려면 ConsumerFactory +
 * `kafkaListenerContainerFactory` 빈을 직접 정의해야 한다.
 *
 * @EnableKafka 가 있어야 @KafkaListener annotation 이 스캔된다.
 */
@Configuration
@EnableKafka
class KafkaConsumerConfig {

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
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to true,
        )
        return DefaultKafkaConsumerFactory(props)
    }

    /**
     * Spring 이 @KafkaListener 에 default 로 찾는 빈 이름이 `kafkaListenerContainerFactory` — 이름 변경 금지.
     */
    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(consumerFactory)
        return factory
    }
}
