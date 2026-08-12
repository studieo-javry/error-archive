package org.studieojavry.coreapi.errorcase.shared.infrastructure.activity

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort

/**
 * Fallback adapter — Kafka adapter 가 비활성(`noti.publisher.mode != kafka`)일 때 등록.
 *
 * `@ConditionalOnMissingBean` 으로 인해 KafkaActivityEventPublisherAdapter 가 빈으로 올라오면
 * 본 빈은 자동 skip. 운영 환경의 자유도 확보 — 잔디 시스템이 *옵션* 인 환경(테스트/스테이징 일부 등)에서도
 * 도메인 UseCase 가 inject 받아 호출 가능.
 *
 * 행위: `publish` 호출이 들어와도 *no-op*. DEBUG 로그만 남김.
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
