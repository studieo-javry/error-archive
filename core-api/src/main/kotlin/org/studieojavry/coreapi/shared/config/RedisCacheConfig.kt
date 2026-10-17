package org.studieojavry.coreapi.shared.config

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

/**
 * core-api 의 **분산(L2) 캐시** — Redis 기반.
 *
 * 인스턴스별로 독립인 Caffeine([CacheConfig]) 과 달리, 여러 인스턴스가 **같은 캐시를 공유**한다.
 * 수평 확장(2대+) 시 (1) 한 인스턴스의 evict 가 전역 반영되고 (2) 재기동 후에도 캐시가 유지되며
 * (3) iam role/profile 같은 **사용자 간 공유 데이터**를 노드마다 중복 적재하지 않는다.
 * (근거·마이그레이션 플레이북: `docs/caching-strategy.md` §10 T1.)
 *
 * **backend 전환**: `core.cache.backend=redis`(기본, local/stg/prod) 면 이 설정이,
 * `caffeine`(test) 면 [CacheConfig] 가 활성. 둘은 **같은 `@Cacheable` 어노테이션**을 공유하므로
 * 애플리케이션 코드는 백엔드를 모른다.
 *
 * **캐시별 TTL** 은 Caffeine 과 동일하게 유지(= 프런트가 체감하는 신선도 불변). 역할/프로필 캐시는
 * 신규로 추가 — role 은 권한 판정이라 **짧게(60s)**, profile 은 준정적이라 5분.
 *
 * **직렬화**: 값은 Jackson 3(`tools.jackson`, 앱 기본 Jackson 과 동일 세대 + Kotlin 모듈) JSON.
 * 캐시 내용은 **이 서비스가 직접 생성**(사용자 입력 아님)하므로 default typing 의 PTV 를 허용적으로 둔다.
 * key 는 사람이 읽을 수 있게 String(`<cacheName>::<key>`) — evict SCAN 과도 맞물린다.
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = ["core.cache.backend"], havingValue = "redis", matchIfMissing = true)
class RedisCacheConfig {

    @Bean
    @Primary
    fun cacheManager(
        connectionFactory: RedisConnectionFactory,
        redisCacheValueSerializer: RedisSerializer<Any>,
    ): CacheManager {
        val base = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(SerializationPair.fromSerializer(StringRedisSerializer()))
            .serializeValuesWith(SerializationPair.fromSerializer(redisCacheValueSerializer))
            // null 은 캐시하지 않는다(@Cacheable 쪽 unless 와 함께) — 특히 role=null(비멤버)·장애 null 을
            // 캐시에 적재해 "접근 거부"가 고착되는 것을 막는다(보수적). 저장 시도 자체는 unless 가 차단.
            .disableCachingNullValues()

        fun ttl(d: Duration) = base.entryTtl(d)

        val perCache = mapOf(
            // ── 신규(2순위): iam 동기호출 자체를 줄이는 캐시 ──
            CacheConfig.CACHE_WORKSPACE_ROLE to ttl(Duration.ofSeconds(60)),     // 권한 판정 → 짧게
            CacheConfig.CACHE_IAM_USER_PROFILE to ttl(Duration.ofMinutes(5)),    // 표시정보 → 준정적
            // ── 기존 대시보드/추천(1순위): Caffeine 과 동일 TTL 로 분산 이전 ──
            CacheConfig.CACHE_SUGGESTED_FOLLOWEES to ttl(Duration.ofMinutes(15)),
            CacheConfig.CACHE_FOLLOWING_FEED to ttl(Duration.ofSeconds(60)),
            CacheConfig.CACHE_RECENT_ACTIVE_CASES to ttl(Duration.ofSeconds(30)),
            CacheConfig.CACHE_WATCHLIST_FEED to ttl(Duration.ofSeconds(30)),
            CacheConfig.CACHE_MY_RECENT_ACTIVITIES to ttl(Duration.ofMinutes(5)),
            CacheConfig.CACHE_SUGGESTION_TOP_AUTHORS to ttl(Duration.ofMinutes(5)),
        )

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(ttl(Duration.ofMinutes(1)))
            .withInitialCacheConfigurations(perCache)
            .build()
    }

    /**
     * Redis 캐시 **값** 직렬화기 — JSON + 루트 타입정보.
     *
     * `GenericJackson2JsonRedisSerializer` 는 루트에 타입(`@class`)을 항상 기록해 enum(`WorkspaceRole`)·
     * data class·중첩 컬렉션·null 마커를 안전하게 왕복시킨다. 내부 매퍼에 **Kotlin 모듈**로
     * data class(파라미터 생성자) 역직렬화를, **JavaTime 모듈**로 `java.time`(LocalDateTime 등)
     * 직렬화를 가능케 한다(앱 기본 Jackson 3 직렬화와는 분리).
     *
     * JavaTime 모듈이 없으면 대시보드 캐시(watchlist-feed/recent-active-cases 등)의 non-empty 결과에
     * 섞인 LocalDateTime 직렬화가 실패해 @Cacheable PUT 이 터지고, 게이트웨이가 503 으로 감싼다
     * (빈 결과는 날짜가 없어 우회됨). timestamp 배열 대신 ISO-8601 문자열로 기록한다.
     */
    @Bean
    fun redisCacheValueSerializer(): RedisSerializer<Any> =
        GenericJackson2JsonRedisSerializer().configure {
            it.registerModule(KotlinModule.Builder().build())
            it.registerModule(JavaTimeModule())
            it.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
}
