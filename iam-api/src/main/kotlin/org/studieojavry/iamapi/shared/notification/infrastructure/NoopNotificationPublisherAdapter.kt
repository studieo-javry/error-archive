package org.studieojavry.iamapi.shared.notification.infrastructure

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.studieojavry.iamapi.shared.notification.application.port.NotificationPublisherPort

private val log = KotlinLogging.logger {}

/**
 * Fallback — Kafka(outbox) adapter 비활성(`noti.publisher.mode != kafka`) 시 활성화.
 *
 * `OutboxNotificationPublisherAdapter` 가 `@ConditionalOnProperty(mode=kafka)` 라 mode 가 kafka 가
 * 아닌 환경(local 미설정 / test / http 모드)에서는 `NotificationPublisherPort` 빈이 아예 없어
 * 이를 필수 의존하는 UseCase(FollowUser/InviteMember) 가 컨텍스트 로드에 실패한다.
 * → `@ConditionalOnMissingBean` 으로 no-op 빈을 항상 보장(발화 호출은 무해하게 drop).
 *
 * 주의: `NotificationPublisherPort` 는 `@Repository` 라 PersistenceExceptionTranslation AOP 대상이고,
 * Spring Boot 기본 `proxy-target-class=true` 로 CGLIB 프록시된다. 따라서 impl 은 **익명 object(final) 가
 * 아닌 open class** 여야 subclass 가능(익명 object 면 "Cannot subclass final class" 로 실패).
 */
open class NoopNotificationPublisherAdapter : NotificationPublisherPort {
    override fun publishNewFollower(event: NotificationPublisherPort.NewFollowerEvent) {
        log.debug { "[noop-notification] dropped new-follower: recipient=${event.recipientUserId}" }
    }

    override fun publishWorkspaceInvitation(event: NotificationPublisherPort.WorkspaceInvitationEvent) {
        log.debug { "[noop-notification] dropped invitation: invitation=${event.invitationId}" }
    }
}

@Configuration(proxyBeanMethods = false)
open class NoopNotificationPublisherAdapterConfig {

    @Bean
    @ConditionalOnMissingBean(NotificationPublisherPort::class)
    fun noopNotificationPublisher(): NotificationPublisherPort = NoopNotificationPublisherAdapter()
}
