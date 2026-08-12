package org.studieojavry.coreapi.errorcase.case.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort
import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort.AuthorSummary

/**
 * iam-api 의 `POST /internal/users/profiles` 호출.
 *
 * IamApiRestClient 가 internal-auth 토큰 (iss=core-api, aud=iam-api, sub=현재 사용자) 을 자동 주입.
 * 인증 컨텍스트 없으면 토큰 없이 호출 → iam-api 가 401 → empty map fallback (목록 응답은 정상 표시,
 * 작성자 칩만 누락).
 */
@Component
class IamAuthorSummaryAdapter(
    private val iamApiRestClient: RestClient,
) : AuthorSummaryReaderPort {

    private val logger = KotlinLogging.logger {}

    override fun read(authorUserIds: Collection<Long>, viewerUserId: Long?): Map<Long, AuthorSummary> {
        if (authorUserIds.isEmpty()) return emptyMap()
        val distinct = authorUserIds.toSet()

        return runCatching {
            val body = iamApiRestClient.post()
                .uri("/internal/users/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BatchProfileRequest(distinct.toList(), viewerUserId))
                .retrieve()
                .body<BatchProfileResponse>()
                ?: return@runCatching emptyMap<Long, AuthorSummary>()

            body.items.associateBy(
                keySelector = { it.userId },
                valueTransform = {
                    AuthorSummary(
                        userId = it.userId,
                        handle = it.handle,
                        displayName = it.displayName,
                        avatarUrl = it.avatarUrl,
                        bio = it.bio,
                        isFollowing = it.isFollowing,
                    )
                }
            )
        }.getOrElse { e ->
            logger.warn(e) { "iam batch profile fetch failed — skip author enrichment (size=${distinct.size})" }
            emptyMap()
        }
    }

    private data class BatchProfileRequest(
        val userIds: List<Long>,
        val viewerUserId: Long?,
    )

    private data class BatchProfileResponse(
        val items: List<Item>,
    ) {
        data class Item(
            val userId: Long,
            val handle: String,
            val displayName: String,
            val avatarUrl: String?,
            val bio: String?,
            val isFollowing: Boolean,
        )
    }
}
