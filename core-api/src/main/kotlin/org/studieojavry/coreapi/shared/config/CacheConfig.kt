package org.studieojavry.coreapi.shared.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCache
import org.springframework.cache.support.SimpleCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

/**
 * core-api 의 인메모리 캐시 — Caffeine 기반.
 *
 * **사용 정책**: 사용자별 *추천* / *signal 계산* / *dashboard aggregation* 등
 * *분/초 단위로 동일 결과 반환* 되는 항목만. 도메인 데이터 (case 본문, 상태 등) 는 *캐싱 X*.
 *
 * **캐시별 spec 분리** — 데이터 신선도 요구에 따라 TTL 다르게. 자세한 정책은
 * `docs/caching-strategy.md` 참조.
 *
 * | 캐시 | 키 | TTL | 근거 |
 * |---|---|---|---|
 * | `suggested-followees` | `userId:limit` | 15분 | 발견용, stale 허용 |
 * | `following-feed` | `userId:limit` | 60초 | 팔로잉 발견 피드, iam-api 왕복 완충 |
 * | `recent-active-cases` | `userId:windowDays:limit` | 30초 | 내 케이스, 대응 지연 최소화 |
 * | `watchlist-feed` | `userId:limit` | 30초 | 관심 케이스 track |
 * | `my-recent-activities` | `target:viewer` | 5분 | viewer 별 visibility 필터 결과 분리 |
 *
 * **키에 param 포함** — 같은 userId 라도 다른 limit/windowDays 요청이 서로의 결과를 오염시키지
 * 않도록 param 을 키에 넣는다. 무효화는 `DashboardCacheInvalidator` 가 `userId` prefix 로 모든
 * variant 를 한 번에 제거한다 (`@CacheEvict` 단일 key 불가). following-feed 는 evict 대상이
 * 아니며 (팔로우/신규 case fan-out 비용) 60초 TTL 자연 만료에 의존.
 */
@Configuration
@EnableCaching
class CacheConfig {

    @Bean
    fun cacheManager(): CacheManager {
        val mgr = SimpleCacheManager()
        mgr.setCaches(
            listOf(
                buildCache(CACHE_SUGGESTED_FOLLOWEES, ttlMinutes = 15, maxSize = 10_000),
                buildCache(CACHE_FOLLOWING_FEED, ttlSeconds = 60, maxSize = 10_000),
                buildCache(CACHE_RECENT_ACTIVE_CASES, ttlSeconds = 30, maxSize = 10_000),
                buildCache(CACHE_WATCHLIST_FEED, ttlSeconds = 30, maxSize = 10_000),
                buildCache(CACHE_MY_RECENT_ACTIVITIES, ttlMinutes = 5, maxSize = 10_000),
            )
        )
        return mgr
    }

    private fun buildCache(
        name: String,
        ttlMinutes: Long? = null,
        ttlSeconds: Long? = null,
        maxSize: Long,
    ): CaffeineCache {
        val builder = Caffeine.newBuilder().maximumSize(maxSize)
        when {
            ttlSeconds != null -> builder.expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
            ttlMinutes != null -> builder.expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
        }
        return CaffeineCache(name, builder.build())
    }

    companion object {
        const val CACHE_SUGGESTED_FOLLOWEES = "suggested-followees"
        const val CACHE_FOLLOWING_FEED = "following-feed"
        const val CACHE_RECENT_ACTIVE_CASES = "recent-active-cases"
        const val CACHE_WATCHLIST_FEED = "watchlist-feed"
        const val CACHE_MY_RECENT_ACTIVITIES = "my-recent-activities"
    }
}
