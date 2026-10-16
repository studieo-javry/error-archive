package org.studieojavry.coreapi.errorcase.shared.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.studieojavry.coreapi.errorcase.shared.application.port.IamUserQueryPort

/**
 * iam-api 의 user 도메인 조회 어댑터.
 *
 *  - searchByDisplayNamePrefix → GET /api/v1/users/search?q=&limit=
 *  - resolveByDisplayName     → GET /api/v1/users/search?q={name}&limit=2 후 정확 일치 1건만 매칭
 *  - findByIds                → 단건 GET /api/v1/users/{id} 반복 (작은 N 이라 OK. 추후 batch endpoint 도입 가능)
 *
 * 모든 호출은 IamApiRestClientConfig 의 interceptor 가 core-api 발급 internal JWT(X-Internal-Auth) 주입.
 * iam-api 가 5xx/timeout 누적되면 같은 `iamApi` CircuitBreaker 가 OPEN → fallback 으로 *조용히* 빈 결과/null.
 *  (멘션 자동완성/userId 해석 실패는 사용자 경험에 *덜 치명적* — 발송이 안 갈 뿐.)
 */
@Component
class IamUserQueryAdapter(
    @Qualifier("iamApiRestClient") private val iamApi: RestClient,
    cbFactory: CircuitBreakerFactory<*, *>,
    /** 단건 프로필 캐시 단위 — findByIds 가 id 마다 외부 빈 호출로 캐시를 태운다. */
    private val profileReader: IamUserProfileReader,
) : IamUserQueryPort {

    private val log = KotlinLogging.logger {}
    private val cb = cbFactory.create("iamApi")

    override fun searchByDisplayNamePrefix(q: String, limit: Int): List<IamUserQueryPort.UserSummary> = cb.run(
        {
            try {
                val items = iamApi.get()
                    .uri("/api/v1/users/search?q={q}&limit={l}", q, limit.coerceIn(1, 20))
                    .retrieve()
                    .body(Array<IamUserItem>::class.java)
                    ?: emptyArray()
                items.map { IamUserQueryPort.UserSummary(it.userId, it.displayName, it.avatarUrl, it.handle) }
            } catch (ex: RestClientResponseException) {
                log.warn(ex) { "iam-api user search failed: status=${ex.statusCode}" }
                emptyList()
            }
        },
        { t -> log.warn(t) { "iam-api unavailable on searchByDisplayNamePrefix → empty" }; emptyList() }
    )

    override fun resolveByDisplayName(displayName: String): Long? {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return null
        val hits = searchByDisplayNamePrefix(trimmed, 2)
        // 정확 일치 (대소문자 무시) 1건만 → 매핑. 동명이인이거나 0건이면 null.
        val exact = hits.filter { it.displayName.equals(trimmed, ignoreCase = true) }
        return if (exact.size == 1) exact.single().userId else null
    }

    override fun resolveByHandle(handle: String): Long? {
        val trimmed = handle.trim().removePrefix("@")
        if (trimmed.isEmpty()) return null
        // iam 검색은 handle prefix 도 매칭한다. handle 정확 일치(대소문자 무시) 1건만 채택.
        val hits = searchByDisplayNamePrefix(trimmed, 5)
        val exact = hits.filter { it.handle?.equals(trimmed, ignoreCase = true) == true }
        return if (exact.size == 1) exact.single().userId else null
    }

    /**
     * id 마다 [IamUserProfileReader.fetch] 로 위임 — userId 단위 캐시를 태운다(외부 빈 호출이라 프록시 경유).
     * 캐시에 있는 작성자는 iam 호출 없이 반환되고, 없는 id 만 실제 호출한다(부분 캐시). 각 fetch 는
     * 개별 CB fallback(null)이라 일부 실패해도 나머지는 반환된다(mapNotNull).
     */
    override fun findByIds(ids: Collection<Long>): List<IamUserQueryPort.UserSummary> =
        ids.distinct().mapNotNull { profileReader.fetch(it) }

    private data class IamUserItem(val userId: Long, val displayName: String, val avatarUrl: String?, val handle: String? = null)
}