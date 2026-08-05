package org.studieojavry.coreapi.errorcase.shared.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.studieojavry.coreapi.errorcase.shared.application.port.FollowedUserReaderPort

/**
 * iam-api 의 `GET /internal/users/{id}/following-ids` 호출.
 * IamApiRestClient 가 internal-auth 토큰(iss=core-api, aud=iam-api) 자동 주입.
 */
@Component
class IamFollowedUserReaderAdapter(
    private val iamApiRestClient: RestClient,
) : FollowedUserReaderPort {

    private val logger = KotlinLogging.logger {}

    override fun findFollowingIds(userId: Long, size: Int): List<Long> {
        return runCatching {
            iamApiRestClient.get()
                .uri("/internal/users/$userId/following-ids?size=$size")
                .retrieve()
                .body<Response>()
                ?.userIds
                ?: emptyList()
        }.getOrElse { e ->
            logger.warn(e) { "iam following-ids fetch failed (userId=$userId) — fallback emptyList" }
            emptyList()
        }
    }

    private data class Response(val userIds: List<Long>)
}
