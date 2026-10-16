package org.studieojavry.notiapi.notification.infrastructure.sse

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.data.redis.listener.ChannelTopic
import tools.jackson.databind.ObjectMapper

/**
 * `noti.realtime.backend=redis`(기본) 일 때만 활성 — 노드 간 실시간 fan-out 구독 배선.
 *
 * RedisMessageListenerContainer 는 Spring Boot 가 자동 구성하지 않으므로 직접 등록하고,
 * [RedisRealtimeSubscriber] 를 [RedisRealtimeChannel] 토픽에 바인딩한다. 각 noti 인스턴스가
 * 이 컨테이너를 돌려 같은 채널을 구독한다.
 */
@Configuration
@ConditionalOnProperty(name = ["noti.realtime.backend"], havingValue = "redis", matchIfMissing = true)
class RedisRealtimeConfig {

    @Bean
    fun realtimeSubscriber(broker: NotificationSseBroker, objectMapper: ObjectMapper): RedisRealtimeSubscriber =
        RedisRealtimeSubscriber(broker, objectMapper)

    @Bean
    fun realtimeListenerContainer(
        connectionFactory: RedisConnectionFactory,
        realtimeSubscriber: RedisRealtimeSubscriber,
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        container.addMessageListener(realtimeSubscriber, ChannelTopic(RedisRealtimeChannel.NAME))
        return container
    }
}
