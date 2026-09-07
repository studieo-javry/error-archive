package org.studieojavry.publishapi.shared.infrastructure.kafka

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

@Configuration
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "kafka")
class KafkaProducerConfig {

    @Bean
    fun publishProducerFactory(
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
    fun publishKafkaTemplate(
        producerFactory: ProducerFactory<String, String>,
    ): KafkaTemplate<String, String> = KafkaTemplate(producerFactory)
}
