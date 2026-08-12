package org.studieojavry.coreapi.errorcase.shared.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceMemberReaderPort

/**
 * iam-api 의 `GET /internal/workspaces/{id}/member-ids` 호출.
 * IamApiRestClient 가 internal-auth 토큰(iss=core-api, aud=iam-api) 자동 주입.
 */
@Component
class IamWorkspaceMemberReaderAdapter(
    private val iamApiRestClient: RestClient,
) : WorkspaceMemberReaderPort {

    private val logger = KotlinLogging.logger {}

    override fun findMemberIds(workspaceId: Long): List<Long> {
        return runCatching {
            iamApiRestClient.get()
                .uri("/internal/workspaces/$workspaceId/member-ids")
                .retrieve()
                .body<Response>()
                ?.userIds
                ?: emptyList()
        }.getOrElse { e ->
            logger.warn(e) { "iam workspace member fetch failed (workspaceId=$workspaceId) — fallback emptyList" }
            emptyList()
        }
    }

    override fun findCoMemberIds(userId: Long, size: Int): List<Long> {
        return runCatching {
            iamApiRestClient.get()
                .uri("/internal/workspaces/co-members?userId=$userId&size=$size")
                .retrieve()
                .body<Response>()
                ?.userIds
                ?: emptyList()
        }.getOrElse { e ->
            logger.warn(e) { "iam workspace co-members fetch failed (userId=$userId) — fallback emptyList" }
            emptyList()
        }
    }

    private data class Response(val userIds: List<Long>)
}
