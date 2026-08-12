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

/**
 * Spring Boot 4 의 KafkaAutoConfiguration 부재 → ConsumerFactory + listenerContainerFactory 직접 등록.
 * `@KafkaListener` 가 default 로 `kafkaListenerContainerFactory` 빈을 찾으므로 이름 고정.
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
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to true,
        )
        return DefaultKafkaConsumerFactory(props)
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(consumerFactory)
        return factory
    }
}
