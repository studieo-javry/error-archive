package org.studieojavry.iamapi.social.infrastructure.activity

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.studieojavry.iamapi.social.application.port.ActivityEventPublisherPort

/**
 * Fallback — Kafka adapter 비활성(`noti.publisher.mode != kafka`) 시 활성화. publish 는 DEBUG 로그만.
 */
@Configuration
class NoopActivityEventPublisherAdapterConfig {

    private val log = KotlinLogging.logger {}

    @Bean
    @ConditionalOnMissingBean(ActivityEventPublisherPort::class)
    fun noopActivityEventPublisher(): ActivityEventPublisherPort = object : ActivityEventPublisherPort {
        override fun publish(event: ActivityEventPublisherPort.ActivityEvent) {
            log.debug { "[noop-activity] dropped: key=${event.idempotencyKey} type=${event.type}" }
        }
    }
}
