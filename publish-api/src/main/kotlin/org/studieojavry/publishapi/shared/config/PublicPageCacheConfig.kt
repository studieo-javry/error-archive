package org.studieojavry.publishapi.shared.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 공개 페이지(`/p/{slug}`) HTML 렌더 캐시. 렌더가 CPU 병목(부하테스트 확인: 캐시 시 처리량 ~7.6x).
 *
 *  - 캐시 대상: 렌더 결과 HTML 문자열만. 컨트롤러의 조회수/쿠키 로직은 캐시 밖 → view_count 정확 유지.
 *  - 무효화: key 에 `updatedAt` 포함 → 재발행·메타편집(모두 updatedAt 갱신) 시 새 key 로 자동 갱신.
 *  - 크기 가드: 엔트리 ~31KB, maximumSize 500 → 최대 ~15MB, 1시간 미접근 시 회수.
 */
@Configuration
@EnableCaching
class PublicPageCacheConfig {

    @Bean
    fun cacheManager(): CacheManager =
        CaffeineCacheManager(PUBLIC_PAGE_CACHE).apply {
            setCaffeine(
                Caffeine.newBuilder()
                    .maximumSize(500)
                    .expireAfterAccess(Duration.ofHours(1)),
            )
        }

    companion object {
        const val PUBLIC_PAGE_CACHE = "publicPage"
    }
}
