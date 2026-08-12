package org.studieojavry.coreapi.errorcase.shared.infrastructure.noti

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

/**
 * Spring Boot 4 는 spring-kafka 의 KafkaAutoConfiguration 을 별도 starter로 분리했고
 * 현재 의존성으로는 자동 빈 등록이 일어나지 않는다. `noti.publisher.mode=kafka` 일 때만 활성화되도록
 * 같은 조건으로 producer factory + KafkaTemplate 를 직접 정의한다.
 *
 * 설정값은 application yml 의 `spring.kafka.*` 와 동일 의미 — 1:1 매핑.
 */
@Configuration
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class KafkaProducerConfig {

    @Bean
    fun notificationProducerFactory(
        @Value("\${spring.kafka.bootstrap-servers:localhost:9094}") bootstrapServers: String,
        @Value("\${spring.kafka.producer.acks:1}") acks: String,
        @Value("\${spring.kafka.producer.properties.delivery.timeout.ms:8000}") deliveryTimeoutMs: Int,
        @Value("\${spring.kafka.producer.properties.request.timeout.ms:5000}") requestTimeoutMs: Int,
    ): ProducerFactory<String, String> {
        val props = mapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to acks,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to deliveryTimeoutMs,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to requestTimeoutMs,
        )
        return DefaultKafkaProducerFactory(props)
    }

    @Bean
    fun notificationKafkaTemplate(
        producerFactory: ProducerFactory<String, String>,
    ): KafkaTemplate<String, String> = KafkaTemplate(producerFactory)
}
