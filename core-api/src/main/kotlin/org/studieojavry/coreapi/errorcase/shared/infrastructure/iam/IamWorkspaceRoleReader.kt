package org.studieojavry.coreapi.errorcase.shared.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.shared.config.CacheConfig

/**
 * iam-api 의 **워크스페이스 역할 조회** — **캐시 적용 단위**.
 *
 * 권한 판정(`ErrorCaseAccess`) 이 case 읽기/쓰기/관리마다 `getViewerRole` 을 부르는 hot path 라,
 * (userId, workspaceId) 단위로 캐시해 iam 동기호출(및 CB executor 점유) 자체를 줄인다.
 *
 * **왜 래퍼([ViewerRole])인가**: 캐시 값이 enum 단독이면 JSON 루트에 타입(`@class`)을 붙일 수 없어
 * String 으로 역직렬화된다(ClassCastException). enum 을 data class 필드로 감싸면 루트는 POJO 라
 * 타입이 기록되고 내부 enum 은 필드 타입으로 정확히 복원된다.
 *
 * self-invocation 은 프록시를 우회하므로, 캐시 대상 메서드는 **별도 빈**에 두고
 * [IamWorkspaceQueryAdapter.getViewerRole] 이 외부 빈 호출로 캐시를 태운다.
 *
 * - 403(비멤버)/404(없음) → `role=null` (비즈니스 결과, CB 실패 아님), **캐시 안 함**(`unless`).
 * - 5xx/timeout → CB 집계 후 fallback 이 [IamApiUnavailableException] throw → **캐시 안 함**(예외).
 */
@Component
class IamWorkspaceRoleReader(
    @Qualifier("iamApiRestClient") private val iamApi: RestClient,
    cbFactory: CircuitBreakerFactory<*, *>,
) {
    private val log = KotlinLogging.logger {}
    private val cb = cbFactory.create("iamApi")

    /** 캐시 저장이 가능하도록 enum 을 감싼 홀더. */
    data class ViewerRole(val role: WorkspaceQueryPort.WorkspaceRole?)

    @Cacheable(
        cacheNames = [CacheConfig.CACHE_WORKSPACE_ROLE],
        key = "#userId + ':' + #workspaceId",
        unless = "#result.role == null",
    )
    fun fetchViewerRole(userId: Long, workspaceId: Long): ViewerRole = ViewerRole(
        cb.run(
            {
                try {
                    val view = iamApi.get()
                        .uri("/api/v1/workspaces/{id}", workspaceId)
                        .retrieve()
                        .body(IamWorkspaceView::class.java)
                    view?.viewerRole?.let { WorkspaceQueryPort.WorkspaceRole.fromCode(it) }
                } catch (ex: RestClientResponseException) {
                    if (ex.statusCode in listOf(HttpStatusCode.valueOf(403), HttpStatusCode.valueOf(404))) {
                        return@run null
                    }
                    log.warn(ex) { "iam-api workspace role check failed: status=${ex.statusCode}" }
                    throw ex
                }
            },
            { throwable ->
                log.warn(throwable) { "iam-api unavailable on getViewerRole, circuit breaker fallback" }
                throw IamApiUnavailableException("workspace role check unavailable", throwable)
            },
        ),
    )

    /** GET /workspaces/{id} 응답에서 viewerRole 만 발췌(나머지 필드는 무시). */
    private data class IamWorkspaceView(val viewerRole: String? = null)
}
