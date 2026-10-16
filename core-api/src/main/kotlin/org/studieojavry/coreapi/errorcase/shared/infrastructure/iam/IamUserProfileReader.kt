package org.studieojavry.coreapi.errorcase.shared.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.studieojavry.coreapi.errorcase.shared.application.port.IamUserQueryPort
import org.studieojavry.coreapi.shared.config.CacheConfig

/**
 * iam-api 의 **단건 사용자 프로필**(userId → 표시정보) 조회 — **캐시 적용 단위**.
 *
 * 피드/목록 응답은 아이템마다 작성자 표시정보(avatar/displayName/handle)를 iam 에서 hydrate 한다.
 * 같은 작성자가 여러 아이템/여러 요청에 반복 등장하므로 **userId 단위 캐시 적중률이 높다**.
 *
 * `@Cacheable` 은 **단건 메서드**에만 걸 수 있고(컬렉션 결과는 per-element 캐시 불가),
 * 자기호출(self-invocation)은 프록시를 우회하므로, 이 단건 fetch 를 **별도 빈**으로 분리해
 * [IamUserQueryAdapter.findByIds] 가 **외부 빈 호출**로 id 마다 캐시를 태운다.
 *
 * null(404/장애 fallback)은 캐시하지 않는다(`unless` + disableCachingNullValues) — 방금 생성된
 * 사용자나 일시 장애가 "없음"으로 고착되지 않게.
 */
@Component
class IamUserProfileReader(
    @Qualifier("iamApiRestClient") private val iamApi: RestClient,
    cbFactory: CircuitBreakerFactory<*, *>,
) {
    private val log = KotlinLogging.logger {}
    private val cb = cbFactory.create("iamApi")

    @Cacheable(cacheNames = [CacheConfig.CACHE_IAM_USER_PROFILE], key = "#id", unless = "#result == null")
    fun fetch(id: Long): IamUserQueryPort.UserSummary? = cb.run(
        {
            try {
                iamApi.get()
                    .uri("/api/v1/users/{id}", id)
                    .retrieve()
                    .body(IamPublicProfile::class.java)
                    ?.let { IamUserQueryPort.UserSummary(it.userId, it.displayName, it.avatarUrl, it.handle) }
            } catch (ex: RestClientResponseException) {
                if (ex.statusCode.value() == 404) null
                else { log.warn(ex) { "iam-api user fetch failed: id=$id status=${ex.statusCode}" }; null }
            }
        },
        { t -> log.warn(t) { "iam-api unavailable on user profile fetch id=$id → null" }; null },
    )

    private data class IamPublicProfile(
        val userId: Long,
        val displayName: String,
        val avatarUrl: String?,
        val handle: String? = null,
    )
}
