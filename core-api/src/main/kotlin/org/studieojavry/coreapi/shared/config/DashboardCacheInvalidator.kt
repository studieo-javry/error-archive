package org.studieojavry.coreapi.shared.config

import com.github.benmanes.caffeine.cache.Cache
import org.slf4j.LoggerFactory
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component

/**
 * 홈 대시보드 **+ my-page/author-profile** 위젯의 사용자별 캐시를 evict 하는 helper.
 *
 * (구 `HomeDashboardCacheInvalidator` — `my-recent-activities` 는 홈이 아닌 my-page 위젯으로
 * 이전됐으므로 "Home" 한정 네이밍을 버리고 대시보드 위젯 전반을 지칭하도록 개명.)
 *
 * mutation UseCase 가 자기(혹은 영향받는) 사용자의 캐시를 무효화할 때 주입해 호출.
 *
 * **왜 programmatic eviction 인가**
 * 캐시 키가 `userId` 단독이 아니라 `userId:param...` 형태다:
 *  - recent-active-cases    → `userId:windowDays:limit`
 *  - watchlist-feed         → `userId:limit`
 *  - my-recent-activities   → `target:viewer`
 * 단일 key 를 지정하는 `@CacheEvict` 로는 한 사용자의 *모든 param variant* 를 지울 수 없다
 * (과거 `key = "#userId"` 는 복합 키와 절대 매치되지 않아 evict 가 no-op 이었다). 그래서
 * Caffeine native map 에서 `"<userId>"` 또는 `"<userId>:*"` prefix 키를 직접 제거한다.
 *
 * 정책:
 *  - `evictRecentActive(userId)`      : 홈 recent-active-cases (그 사용자의 모든 variant)
 *  - `evictWatchlistFeed(userId)`     : 홈 watchlist-feed
 *  - `evictMyRecentActivities(userId)`: my-page/author-profile recent-activities.
 *    key = `target:viewer` 이므로 `target == userId` 인 *모든 viewer* entry (self + 방문자) 를 함께 제거.
 *
 * comment/step/solution 등이 *남* 케이스에 발생했을 때 case owner 는 evict 하지만, 그 케이스를
 * watchlist 한 다른 사용자들의 watchlist-feed 는 watcher fan-out 비용 때문에 evict 하지 않고
 * 30초 TTL 자연 만료에 의존한다 (MVP1).
 *
 * evict 실패가 mutation 을 롤백시키면 안 되므로 내부에서 catch + WARN 로그만 남기고 삼킨다
 * (**never throws**). 호출부의 추가 `runCatching` 은 belt-and-suspenders.
 */
@Component
class DashboardCacheInvalidator(
    private val cacheManager: CacheManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun evictRecentActive(userId: Long) = evictByUser(CacheConfig.CACHE_RECENT_ACTIVE_CASES, userId)

    fun evictWatchlistFeed(userId: Long) = evictByUser(CacheConfig.CACHE_WATCHLIST_FEED, userId)

    fun evictMyRecentActivities(userId: Long) = evictByUser(CacheConfig.CACHE_MY_RECENT_ACTIVITIES, userId)

    /** 사용자 자기 액션 후 홈 두 위젯 동시 evict (편의 메서드). */
    fun evictBoth(userId: Long) {
        evictRecentActive(userId)
        evictWatchlistFeed(userId)
    }

    /**
     * `cacheName` 에서 키가 `"<userId>"` 이거나 `"<userId>:"` 로 시작하는 모든 entry 제거.
     * 경계로 ':' 를 포함하므로 userId=4 가 userId=42 의 entry 를 잘못 지우지 않는다.
     */
    private fun evictByUser(cacheName: String, userId: Long) {
        try {
            val cache = cacheManager.getCache(cacheName) ?: return
            @Suppress("UNCHECKED_CAST")
            val native = cache.nativeCache as? Cache<Any, Any> ?: return
            val exact = userId.toString()
            val prefix = "$exact:"
            native.asMap().keys.removeIf { key ->
                val k = key.toString()
                k == exact || k.startsWith(prefix)
            }
        } catch (e: Exception) {
            log.warn("dashboard cache evict failed: cache={} userId={}", cacheName, userId, e)
        }
    }
}
