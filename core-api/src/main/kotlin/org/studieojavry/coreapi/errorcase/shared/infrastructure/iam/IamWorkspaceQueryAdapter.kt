package org.studieojavry.coreapi.errorcase.shared.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/**
 * iam-api 의 워크스페이스 도메인 조회용 어댑터.
 *
 * - getViewerRole: GET /api/v1/workspaces/{id} → 200(viewerRole 파싱) / 403,404(null=비멤버) / 5xx
 * - getAvailableWorkspaces: GET /api/v1/workspaces
 *
 * 모든 호출은 IamApiRestClientConfig 의 interceptor 가 core-api 발급 internal JWT(X-Internal-Auth)를 주입.
 *
 * CircuitBreaker: iam-api 가 5xx/timeout 누적되면 OPEN → 해당 시간 동안 호출 차단,
 * fallback 으로 즉시 응답 (cascading failure 방지).
 *  - getViewerRole fallback: IamApiUnavailableException (트랜잭션 롤백 + 5xx 응답)
 *  - getAvailableWorkspaces fallback: emptyList() (UI 가 빈 목록을 보여주는 degraded UX)
 */
@Component
class IamWorkspaceQueryAdapter(
    @Qualifier("iamApiRestClient") private val iamApi: RestClient,
    cbFactory: CircuitBreakerFactory<*, *>,
    /** 역할 조회는 캐시 단위([IamWorkspaceRoleReader])로 위임 — hot path 의 iam 호출을 줄인다. */
    private val roleReader: IamWorkspaceRoleReader,
) : WorkspaceQueryPort {

    private val log = KotlinLogging.logger {}

    /** 모든 iam 호출은 같은 CB 를 공유 — 충분한 샘플 누적으로 정확한 판단 */
    private val cb = cbFactory.create("iamApi")

    /**
     * 캐시 히트 시 iam 호출(및 CB executor 점유) 자체가 **발생하지 않는다** — 권한 판정 hot path 의
     * iam 동기호출을 줄여, bulkhead 로 막아둔 장애 부하 자체를 낮춘다. 실제 조회·CB·캐시는
     * [IamWorkspaceRoleReader.fetchViewerRole] 이 담당(외부 빈 호출이라 프록시 경유 → 캐시 적용).
     */
    override fun getViewerRole(userId: Long, workspaceId: Long): WorkspaceQueryPort.WorkspaceRole? =
        roleReader.fetchViewerRole(userId, workspaceId).role

    override fun getAvailableWorkspaces(userId: Long): List<WorkspaceQueryPort.WorkspaceSummary> = cb.run(
        {
            try {
                val items = iamApi.get()
                    .uri("/api/v1/workspaces")
                    .retrieve()
                    .body(Array<IamWorkspaceItem>::class.java)
                    ?: emptyArray()
                items.map { WorkspaceQueryPort.WorkspaceSummary(workpaceId = it.id, name = it.name) }
            } catch (ex: RestClientResponseException) {
                log.warn(ex) { "iam-api list workspaces failed: status=${ex.statusCode}" }
                throw ex
            }
        },
        { throwable ->
            log.warn(throwable) { "iam-api unavailable on getAvailableWorkspaces, returning empty list (degraded UX)" }
            emptyList()
        }
    )

    override fun listMembers(workspaceId: Long): List<WorkspaceQueryPort.MemberSummary> = cb.run(
        {
            try {
                val items = iamApi.get()
                    .uri("/api/v1/workspaces/{id}/members", workspaceId)
                    .retrieve()
                    .body(Array<IamMemberItem>::class.java)
                    ?: emptyArray()
                items.map { WorkspaceQueryPort.MemberSummary(it.userId, it.displayName, it.avatarUrl, it.role) }
            } catch (ex: RestClientResponseException) {
                if (ex.statusCode.value() in listOf(403, 404)) emptyList()
                else { log.warn(ex) { "iam-api list members failed: status=${ex.statusCode}" }; throw ex }
            }
        },
        { t -> log.warn(t) { "iam-api unavailable on listMembers → empty" }; emptyList() }
    )

    private data class IamWorkspaceItem(
        val id: Long,
        val name: String
    )

    private data class IamMemberItem(
        val userId: Long,
        val displayName: String,
        val avatarUrl: String?,
        val role: String,
    )
}

class IamApiUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
