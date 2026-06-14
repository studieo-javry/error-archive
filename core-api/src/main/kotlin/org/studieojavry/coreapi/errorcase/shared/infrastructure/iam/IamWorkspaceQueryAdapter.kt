package org.studieojavry.coreapi.errorcase.shared.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.http.HttpStatusCode
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
    cbFactory: CircuitBreakerFactory<*, *>
) : WorkspaceQueryPort {

    private val log = KotlinLogging.logger {}

    /** 모든 iam 호출은 같은 CB 를 공유 — 충분한 샘플 누적으로 정확한 판단 */
    private val cb = cbFactory.create("iamApi")

    override fun getViewerRole(userId: Long, workspaceId: Long): WorkspaceQueryPort.WorkspaceRole? = cb.run(
        {
            try {
                val view = iamApi.get()
                    .uri("/api/v1/workspaces/{id}", workspaceId)
                    .retrieve()
                    .body(IamWorkspaceView::class.java)
                view?.viewerRole?.let { WorkspaceQueryPort.WorkspaceRole.fromCode(it) }
            } catch (ex: RestClientResponseException) {
                if (ex.statusCode in listOf(HttpStatusCode.valueOf(403), HttpStatusCode.valueOf(404))) {
                    // 403(비멤버)/404(없음) 은 비즈니스 결과(=역할 없음) — CB 실패로 카운트 X
                    return@run null
                }
                // 5xx 는 CB 가 실패로 카운트하도록 다시 throw
                log.warn(ex) { "iam-api workspace role check failed: status=${ex.statusCode}" }
                throw ex
            }
        },
        { throwable ->
            log.warn(throwable) { "iam-api unavailable on getViewerRole, circuit breaker fallback" }
            throw IamApiUnavailableException("workspace role check unavailable", throwable)
        }
    )

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

    /** GET /workspaces/{id} 응답에서 viewerRole 만 발췌(나머지 필드는 무시). */
    private data class IamWorkspaceView(
        val viewerRole: String? = null
    )

    private data class IamMemberItem(
        val userId: Long,
        val displayName: String,
        val avatarUrl: String?,
        val role: String,
    )
}

class IamApiUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
