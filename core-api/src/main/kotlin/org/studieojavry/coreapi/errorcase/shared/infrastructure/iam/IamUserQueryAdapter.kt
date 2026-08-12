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
                items.map { IamUserQueryPort.UserSummary(it.userId, it.displayName, it.avatarUrl) }
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

    override fun findByIds(ids: Collection<Long>): List<IamUserQueryPort.UserSummary> = cb.run(
        {
            ids.distinct().mapNotNull { id ->
                try {
                    val view = iamApi.get()
                        .uri("/api/v1/users/{id}", id)
                        .retrieve()
                        .body(IamPublicProfile::class.java)
                    view?.let { IamUserQueryPort.UserSummary(it.userId, it.displayName, it.avatarUrl) }
                } catch (ex: RestClientResponseException) {
                    if (ex.statusCode.value() == 404) null else { log.warn(ex) { "iam-api user fetch failed" }; null }
                }
            }
        },
        { t -> log.warn(t) { "iam-api unavailable on findByIds → empty" }; emptyList() }
    )

    private data class IamUserItem(val userId: Long, val displayName: String, val avatarUrl: String?)
    private data class IamPublicProfile(val userId: Long, val displayName: String, val avatarUrl: String?)
}