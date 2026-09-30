package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.shared.config.CacheConfig
import java.time.LocalDateTime

/**
 * 추천 팔로우 fallback 의 **전역** 후보 풀.
 *
 * `findTopOwnersOfRecentPublic`(최근 30d PUBLIC top-authors)은 **userId 무관·결정적**이고 느리게 변하는데,
 * 매 요청 PUBLIC 전체를 Seq/Bitmap Scan(부하 테스트에서 100k 기준 ~19ms)해 신규/라이트 유저 조회의
 * 실제 임계 경로였다. → **전 유저 공유 캐시(TTL 5분)** 로 요청당 full-scan 을 제거한다.
 * 개별 usecase 는 이 풀에서 excluded/중복을 필터하고 필요한 수만 취한다.
 *
 * (⑥ random 은 TABLESAMPLE 로 이미 저렴하고 per-request 다양성이 필요하므로 캐시하지 않는다.)
 */
@Component
class SuggestionFallbackPool(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
) {
    @Cacheable(cacheNames = [CacheConfig.CACHE_SUGGESTION_TOP_AUTHORS], key = "'global'")
    fun topRecentPublicAuthors(): List<Long> =
        errorCaseRepository.findTopOwnersOfRecentPublic(
            LocalDateTime.now().minusDays(WINDOW_DAYS),
            POOL_SIZE,
        )

    companion object {
        private const val WINDOW_DAYS = 30L
        private const val POOL_SIZE = 200
    }
}
