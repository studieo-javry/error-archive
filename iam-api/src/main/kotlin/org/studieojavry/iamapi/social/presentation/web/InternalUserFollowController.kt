package org.studieojavry.iamapi.social.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.iamapi.social.application.usecase.ListFollowingIdsUseCase

/**
 * **Internal-only** — userId 가 *팔로우 중인* followee userId list batch 조회.
 *
 * core-api 가 home 의 "Following · 새 케이스" feed 채울 때 호출. gateway 라우팅 X.
 * internal JWT(aud=iam-api) 검증.
 */
@Tag(name = "users-internal", description = "service-to-service: 사용자 follow 관계 조회.")
@RestController
@RequestMapping("/internal/users")
class InternalUserFollowController(
    private val listFollowingIds: ListFollowingIdsUseCase,
) {
    @Operation(summary = "[internal] userId 가 팔로우 중인 followee userIds")
    @GetMapping("/{userId}/following-ids")
    fun followingIds(
        @PathVariable userId: Long,
        @RequestParam(defaultValue = "200") size: Int,
    ): FollowingIdsResponse {
        return FollowingIdsResponse(userIds = listFollowingIds.invoke(userId, size))
    }

    data class FollowingIdsResponse(val userIds: List<Long>)
}
